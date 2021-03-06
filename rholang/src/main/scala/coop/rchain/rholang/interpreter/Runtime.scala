package coop.rchain.rholang.interpreter

import java.nio.file.Path

import coop.rchain.models.Channel.ChannelInstance.{ChanVar, Quote}
import coop.rchain.models.Expr.ExprInstance.GString
import coop.rchain.models.TaggedContinuation.TaggedCont.ScalaBodyRef
import coop.rchain.models.Var.VarInstance.FreeVar
import coop.rchain.models.{Channel, TaggedContinuation}
import coop.rchain.rholang.interpreter.implicits._
import coop.rchain.rholang.interpreter.storage.implicits._
import coop.rchain.rspace.{install, IStore, LMDBStore}
import monix.eval.Task

import scala.collection.immutable

class Runtime private (val reducer: Reduce[Task],
                       val store: IStore[Channel, Seq[Channel], Seq[Channel], TaggedContinuation])

object Runtime {

  type Name  = String
  type Arity = Int
  type Ref   = Long

  private def introduceSystemProcesses(
      store: IStore[Channel, Seq[Channel], Seq[Channel], TaggedContinuation],
      processes: immutable.Seq[(Name, Arity, Ref)])
    : Seq[Option[(TaggedContinuation, Seq[Seq[Channel]])]] =
    processes.map {
      case (name, arity, ref) =>
        install(store,
                List(Channel(Quote(GString(name)))),
                List((0 until arity).map[Channel, Seq[Channel]](i => ChanVar(FreeVar(i)))),
                TaggedContinuation(ScalaBodyRef(ref)))
    }

  def create(dataDir: Path, mapSize: Long): Runtime = {
    val store =
      LMDBStore.create[Channel, Seq[Channel], Seq[Channel], TaggedContinuation](dataDir, mapSize)

    lazy val dispatcher: Dispatch[Task, Seq[Channel], TaggedContinuation] =
      RholangAndScalaDispatcher.create(store, dispatchTable)

    lazy val dispatchTable = Map(
      0L -> SystemProcesses.stdout,
      1L -> SystemProcesses.stdoutAck(store, dispatcher),
      2L -> SystemProcesses.stderr,
      3L -> SystemProcesses.stderrAck(store, dispatcher)
    )

    val procDefs: immutable.Seq[(Name, Arity, Ref)] = List(
      ("stdout", 1, 0L),
      ("stdoutAck", 2, 1L),
      ("stderr", 1, 2L),
      ("stderrAck", 2, 3L)
    )

    val res: Seq[Option[(TaggedContinuation, Seq[Seq[Channel]])]] =
      introduceSystemProcesses(store, procDefs)

    assert(res.forall(_.isEmpty))

    new Runtime(dispatcher.reducer, store)
  }
}
