package com.github.waterlink.atom

import java.time

import concurrent.Future
import concurrent.duration._
import concurrent.ExecutionContext.Implicits.global

import akka.actor.{ActorSystem, Actor, ActorRef, Props, RootActorPath}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp, MemberRemoved}
import akka.pattern.ask
import akka.util.Timeout

case object GetAtomValue
case class AtomValue[T](name: String, value: T, modified: time.Instant)
case class SetAtomValue[T](value: T, modified: time.Instant)

class Atom[T](name: String, value: T, system: ActorSystem, clock: time.Clock, timeout: Timeout) {
  implicit val tm = timeout

  val actor = system.actorOf(
    Props[AtomActor[T]],
    s"com/github/waterlink/atom/atoms/$name"
  )

  def get: Future[T] = {
    val fut = actor ? GetAtomValue
    fut.map {
      case a: AtomValue[T] => a.value
    }
  }

  def set(value: T): Future[T] = {
    val fut = actor ? SetAtomValue(value, clock.instant)
    fut.map {
      case a: AtomValue[T] => a.value
    }
  }
}

object Atom {
  def apply[T](name: String, value: T)(implicit system: ActorSystem, clock: time.Clock, timeout: Timeout) = {
    new Atom[T](name, value, system, clock, timeout)
  }
}

class AtomActor[T](name: String, initialValue: T, clock: time.Clock, timeout: Timeout) extends Actor {
  implicit val tm = timeout
  val cluster = Cluster(context.system)

  var value = initialValue
  var modified = clock.instant
  var members = IndexedSeq.empty[Member]

  override def preStart: Unit = cluster.subscribe(self, classOf[MemberEvent])
  override def postStop: Unit = cluster.unsubscribe(self)

  def receive = {
    case GetAtomValue => sendBackValue
    case s: SetAtomValue[T] => setValue(s)
    case MemberUp(m) => addMember(m)
    case MemberRemoved(m, _) => removeMember(m)
    case _: MemberEvent => // ignore
  }

  def sendBackValue = {
    sender ! AtomValue[T](name, value, modified)
  }

  def setValue(s: SetAtomValue[T]) = s match {
    case SetAtomValue(newValue, newModified) if newModified.isAfter(modified) =>
      modify(newValue, newModified)
  }

  def modify(newValue: T, newModified: time.Instant) = {
    value = newValue
    modified = newModified
    sendBackValue
    sendValueToMembers
  }

  def sendValueToMembers = {
    members.foreach(sendValueToMember)
  }

  def sendValueToMember(m: Member): Unit = {
    val fut = context.actorSelection(
      RootActorPath(m.address) /
      "com" / "github" / "waterlink" / "atom" / "atoms" /
      name
    ) ? SetAtomValue[T](value, modified)
    fut.onFailure { case _ => sendValueToMember(m) }
  }

  def addMember(member: Member) = {
    members = members :+ member
  }

  def removeMember(member: Member) = {
    members = members.filterNot(_ == member)
  }
}
