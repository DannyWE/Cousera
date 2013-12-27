package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout
import scala.language.postfixOps

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class CheckAck(key: String, id: Long)
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply


  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher


  var kv = Map.empty[String, String]
  var expectedSeq = 0L

  var secondaries = Map.empty[ActorRef, ActorRef]  //replica -> replicator

  var replicators = Set.empty[ActorRef]

  var persistent: ActorRef = context.actorOf(persistenceProps)

  var persistenceAcks = Map.empty[Long, (ActorRef, Persist)] //id -> (requester, persist)

  case class KvOperationRecorder(key: String,
                                 id: Long,
                                 pendingReplicators: Set[ActorRef] = Set(),
                                 requester: ActorRef,
                                 persistencerecordered: Boolean = false
                                  ) {
    val isAcknowleged : Boolean = persistencerecordered && pendingReplicators.isEmpty
  }

  object ReplicaHelper {

    var pendingKvOperations = Map.empty[(String, Long), KvOperationRecorder] //(key,id) -> recorder

    def recorderPersistence(key: String, id: Long) {
      pendingKvOperations.get(key,id).foreach { recorder =>
        checkDone(recorder.copy(persistencerecordered = true))
      }
    }

    def recorderReplication(key: String, id: Long, replicator: ActorRef) {
      pendingKvOperations.get(key, id).foreach { recorder =>
        checkDone(recorder.copy(pendingReplicators = recorder.pendingReplicators - replicator))
      }
    }

    def checkFail(key: String, id: Long) {
      pendingKvOperations.get((key, id)).foreach { recorder =>
        recorder.requester ! OperationFailed(id)
        pendingKvOperations -= Pair(key, id)
      }
    }

    def registerReplication(key: String, id: Long, replicator: ActorRef, requester: ActorRef) {
      val recorder = get(key, id, requester)
      pendingKvOperations += Pair(key, id) -> recorder.copy(pendingReplicators = recorder.pendingReplicators + replicator)
    }

    def registerPersistence(key: String, id: Long, requester: ActorRef) {
      pendingKvOperations += Pair(key,id) -> get(key, id, requester).copy(persistencerecordered = true)
    }

    def get(key: String, id: Long, requester: ActorRef): KvOperationRecorder = {
      pendingKvOperations.getOrElse((key,id), KvOperationRecorder(key = key, id = id, requester = requester))
    }

    def removeReplicator(toRemove: ActorRef) {
      pendingKvOperations.values.foreach { recorder =>
        checkDone(recorder.copy(pendingReplicators = recorder.pendingReplicators - toRemove))
      }
    }

    private def checkDone(recorder: KvOperationRecorder) {
      if (recorder.isAcknowleged) {
        recorder.requester ! OperationAck(recorder.id)
        pendingKvOperations -= Pair(recorder.key, recorder.id)
      } else {
        pendingKvOperations += Pair(recorder.key, recorder.id) -> recorder
      }
    }
  }


  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  val leader: Receive = {
    case Insert(key, value, id) => {
      kv += key -> value
      replicate(key, Some(value), id)
    }

    case Remove(key, id) => {
      kv -= key
      replicate(key, None, id)
    }

    case Get(key, id) => {
      sender ! GetResult(key, kv.get(key), id)
    }

    case Persisted(key, id) => {
      ReplicaHelper.recorderPersistence(key, id)
    }

    case Replicated(key, id) => {
      ReplicaHelper.recorderReplication(key, id, sender)
    }

    case CheckAck(key, id) => {
      ReplicaHelper.checkFail(key, id)
    }


    case Replicas(replicas) => {
      val added = replicas -- secondaries.keySet - self
      val removed = secondaries.keySet -- replicas
      removed.foreach { r =>
        ReplicaHelper.removeReplicator(secondaries(r))
        context stop secondaries(r)
        secondaries -= r
      }

      added.foreach { a =>
        val replicator = context.actorOf(Replicator.props(a))
        secondaries += a -> replicator
        kv.foreach {
          case (k, v) => replicator ! Replicate(k, Some(v), secondaries.size)
        }
      }

    }
  }

  val replica: Receive = {

    case Snapshot(key, valueOption, seq) => {
      if(seq == expectedSeq){
        expectedSeq += 1
        update(key, valueOption)
        persist(key, valueOption, seq)
      } else if (seq < expectedSeq)
        sender ! SnapshotAck(key, seq)
    }

    case Get(key, id) => {
      sender ! GetResult(key, kv.get(key), id)
    }

    case Persisted(key, id) => {
      persistenceAcks(id)._1 ! SnapshotAck(key, id)
      persistenceAcks -= id
    }

  }


  def replicate(key: String, valueOption: Option[String], id: Long) {
    secondaries.values.foreach { replicator =>
      ReplicaHelper.registerReplication(key, id, replicator, sender)
      replicator ! Replicate(key, valueOption, id)
    }
    ReplicaHelper.registerPersistence(key, id, sender)
    persist(key, valueOption, id)
    context.system.scheduler.scheduleOnce(1 second, self, CheckAck(key, id))
  }

  def persist( key: String, valueOption: Option[String], id: Long) {
    val p = Persist(key, valueOption, id)
    persistenceAcks += id -> (sender, p)
    persistent ! p
  }

  def update(key: String, valueOption: Option[String]) {
    if(valueOption.isDefined)
      kv += key -> valueOption.get
    else
      kv -= key
  }

  def repersist():Unit = {
    persistenceAcks.foreach {
      case (id, (_, p)) => persistent ! p
    }
  }

  override def preStart() : Unit= {
    arbiter ! Join
    context.system.scheduler.schedule(0 milliseconds, 100 milliseconds)(repersist)

  }

}