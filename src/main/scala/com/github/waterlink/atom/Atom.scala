package com.github.waterlink.atom

import java.time

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.cluster.{Cluster, Member, RootActorPath}
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp, MemberRemoved}
import akka.pattern.ask._

case object GetAtomValue
case class AtomValue[T](name: String, value: T, modified: time.Instant)
case class SetAtomValue[T](value: T, modified: time.Instant)

class Atom[T](name: String, value: T, system: ActorSystem, clock: time.Clock) {
  val actor = system.actorOf(
    Props[AtomActor],
    "com" / "github" / "waterlink" / "atom" / "atoms" /
    name
  )

  def get: Future[T] = {
    val fut = actor ? GetAtomValue
    fut.map {
      case AtomValue[T](name, value, modified) => value
    }
  }

  def set(value: T): Future[T] = {
    val fut = actor ? SetAtomValue(value, clock.instant)
    fut.map {
      case AtomValue[T](name, value, modified) => value
    }
  }
}

object Atom[T] {
  def apply(name: String, value: T)(implicit system: ActorSystem, clock: time.Clock) = {
    new Atom[T](name, value, system)
  }
}

class AtomActor[T](name: String, initialValue: T, clock: time.Clock) extends Actor {
  val cluster = Cluster(context.system)

  var value = initialValue
  var modified = clock.instant
  var members = IndexedSeq.empty[String]

  override def preStart: Unit = cluster.subscribe(
    self,
    classOf[MemberEvent],
    )
  override def postStop: Unit = cluster.unsubscribe(self)

  def receive = {
    case GetAtomValue => sendBackValue
    case SetAtomValue(newValue, newModified) if newModified.isAfter(modified) =>
      modify(newValue, newModified)
    case MemberUp(m) => addMember(m)
    case MemberRemoved(m) => removeMember(m)
    case _: MemberEvent => // ignore
  }

  def sendBackValue = {
    sender ! AtomValue[T](name, value, modified)
  }

  def modify(newValue: T, newModified: time.Instant) = {
    value = newValue
    modified = newModified
    sendBackValue
    sendValueToMembers
  }

  def sendValueToMembers = {
    for (m <- members) {
      sendValueToMember(m)
    }
  }

  def sendValueToMember(m: String) = {
    val fut = context.actorSelection(
      RootActorPath(m) /
      "com" / "github" / "waterlink" / "atom" / "atoms" /
      name
    ) ? SetAtomValue[T](value, modified)
    fut.onFailure { sendValueToMember(m) }
  }

  def addMember(member: Member) = {
    members = members :+ member.address
  }

  def addMember(member: Member) = {
    members = members.filterNot(_ == member.address)
  }
}
