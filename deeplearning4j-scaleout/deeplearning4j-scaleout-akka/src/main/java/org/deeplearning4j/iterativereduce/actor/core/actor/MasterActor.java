package org.deeplearning4j.iterativereduce.actor.core.actor;

import java.io.DataOutputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.deeplearning4j.datasets.DataSet;
import org.deeplearning4j.iterativereduce.actor.core.Job;
import org.deeplearning4j.iterativereduce.actor.util.ActorRefUtils;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.conf.DeepLearningConfigurable;
import org.deeplearning4j.scaleout.iterativereduce.ComputableMaster;
import org.deeplearning4j.scaleout.iterativereduce.Updateable;
import org.jblas.DoubleMatrix;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.OneForOneStrategy;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.UntypedActor;
import akka.cluster.Cluster;
import akka.contrib.pattern.ClusterReceptionistExtension;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.contrib.pattern.DistributedPubSubMediator.Put;
import akka.dispatch.Futures;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Function;

import com.google.common.collect.Lists;

/**
 * Handles a set of workers and acts as a parameter server for iterative reduce
 * @author Adam Gibson
 *
 */
public abstract class MasterActor<E extends Updateable<?>> extends UntypedActor implements DeepLearningConfigurable,ComputableMaster<E> {

	protected Conf conf;
	protected LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	protected E masterResults;
	protected List<E> updates = new ArrayList<E>();
	protected ActorRef batchActor;
	protected int epochsComplete;
	protected boolean pretrain = true;
	protected final ActorRef mediator = DistributedPubSubExtension.get(getContext().system()).mediator();
	public static String BROADCAST = "broadcast";
	public static String MASTER = "result";
	public static String SHUTDOWN = "shutdown";
	public static String FINISH = "finish";
	Cluster cluster = Cluster.get(getContext().system());
	protected Map<String, WorkerState> workers = new ConcurrentHashMap<>();
	protected Map<String,Job> currentJobs = new ConcurrentHashMap<>();

	ClusterReceptionistExtension receptionist = ClusterReceptionistExtension.get (getContext().system());
	


	//number of batches over time
	protected int partition = 1;
	protected boolean isDone = false;


	/**
	 * Creates the master and the workers with this given conf
	 * @param conf the neural net config to use
	 * @param batchActor the batch actor to use for data set distribution
	 * @param params extra params (implementation dependent)
	 * 
	 */
	public MasterActor(Conf conf,ActorRef batchActor,Object[] params) {
		this.conf = conf;
		this.batchActor = batchActor;
		//subscribe to broadcasts from workers (location agnostic)

		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.MASTER, getSelf()), getSelf());
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.FINISH, getSelf()), getSelf());

		setup(conf);

		context().system().scheduler().schedule(Duration.create(1,TimeUnit.MINUTES), Duration.create(1,TimeUnit.MINUTES), new Runnable() {

			@Override
			public void run() {
				log.info("Current workers " + workers.keySet());
			}

		}, context().dispatcher());


	}

	/**
	 * Creates the master and the workers with this given conf
	 * @param conf the neural net config to use
	 * @param batchActor the batch actor to use for data set distribution
	 * 
	 */
	public MasterActor(Conf conf,ActorRef batchActor) {
		this(conf,batchActor,null);


	}




	@Override
	public void preStart() throws Exception {
		super.preStart();
		mediator.tell(new Put(getSelf()), getSelf());
		ActorRef self = self();
		log.info("Setup master with path " + self.path());
		log.info("Pre start on master " + this.self().path().toString());
	}




	@Override
	public void postStop() throws Exception {
		super.postStop();
		log.info("Post stop on master");
		cluster.unsubscribe(getSelf());
	}




	@Override
	public abstract E compute(Collection<E> workerUpdates,
			Collection<E> masterUpdates);

	@Override
	public abstract void setup(Conf conf);



	/**
	 * Finds the next available worker based on current states
	 * @return the next available worker, blocks till a worker is found
	 */
	protected   WorkerState nextAvailableWorker() {
		boolean foundWork = false;
		//loop till a worker is available; this throttles output
		while(!foundWork) {
			for(WorkerState state : workers.values()) {

				if(state.isAvailable()) {
					log.info("Found next available worker " + state.getWorkerId());
					foundWork = true;
					return  state;
				}
			}
			
			log.info("Waiting on next worker...");
			try {
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		//should never happen
		return null;
	}

	/**
	 * Delegates the list of datasets to the workers.
	 * Each worker receives a portion of work and
	 * changes its status to unavailable, this
	 * will block till a worker is available.
	 * 
	 * This work pull pattern ensures that no duplicate work
	 * is flowing (vs publishing) and also allows for
	 * proper batching of resources and input splits.
	 * @param datasets the datasets to train
	 */
	protected void sendToWorkers(List<DataSet> datasets) {
		int split = this.workers.size();
		final List<List<DataSet>> splitList = Lists.partition(datasets,split);
		partition = splitList.size();
		if(splitList.size() < workers.size()) {
			log.warning("You may want to reconfigure your batch sizes, the current partition rate does not match the number of available workers");
		}
		
		log.info("Found partition of size " + partition);
		for(int i = 0; i < splitList.size(); i++)  {
			final int j = i;


			Future<Void> f = Futures.future(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					log.info("Sending off work for batch " + j);
					//block till there's an available worker
					WorkerState state = nextAvailableWorker();
					List<DataSet> work = new ArrayList<>(splitList.get(j));
					//wrap in a job for additional metadata
					Job j2 = new Job(state.getWorkerId(),(Serializable) work,pretrain);
					currentJobs.put(state.getWorkerId(),j2);
					
					//replicate the network
					mediator.tell(new DistributedPubSubMediator.Publish(state.getWorkerId(),
							j2), getSelf());
					log.info("Delegated work to worker " + state.getWorkerId());
					state.setAvailable(false);

					return null;
				}

			},context().dispatcher());

			ActorRefUtils.throwExceptionIfExists(f, context().dispatcher());

		}


	}


	/**
	 * Splits the input such that each dataset is only one row
	 * @param list the list of datasets to batch
	 */
	protected void splitListIntoRows(List<DataSet> list) {
		Queue<DataSet> q = new ArrayDeque<>(list);
		list.clear();
		log.info("Splitting list in to rows...");
		while(!q.isEmpty()) {
			DataSet pair = q.poll();
			List<DoubleMatrix> inputRows = pair.getFirst().rowsAsList();
			List<DoubleMatrix> labelRows = pair.getSecond().rowsAsList();
			if(inputRows.isEmpty())
				throw new IllegalArgumentException("No input rows found");
			if(inputRows.size() != labelRows.size())
				throw new IllegalArgumentException("Label rows not equal to input rows");

			for(int i = 0; i < inputRows.size(); i++) {
				list.add(new DataSet(inputRows.get(i),labelRows.get(i)));
			}
		}
	}

	/**
	 * Adds a worker state to this master
	 * @param state the state to add
	 */
	public void addWorker(WorkerState state) {
		if(!this.workers.containsKey(state.getWorkerId())) {
			this.workers.put(state.getWorkerId(),state);
			log.info("Added worker with id " + state.getWorkerId());
		}
	}


	@Override
	public abstract void complete(DataOutputStream ds);

	@Override
	public synchronized E getResults() {
		return masterResults;
	}

	@Override
	public SupervisorStrategy supervisorStrategy() {
		return new OneForOneStrategy(0, Duration.Zero(),
				new Function<Throwable, Directive>() {
			public Directive apply(Throwable cause) {
				log.error("Problem with processing",cause);
				return SupervisorStrategy.stop();
			}
		});
	}

	public Conf getConf() {
		return conf;
	}

	public int getEpochsComplete() {
		return epochsComplete;
	}

	public int getPartition() {
		return partition;
	}

	public E getMasterResults() {
		return masterResults;
	}

	public boolean isDone() {
		return isDone;
	}


	public List<E> getUpdates() {
		return updates;
	}


	public ActorRef getBatchActor() {
		return batchActor;
	}




	public ActorRef getMediator() {
		return mediator;
	}




}
