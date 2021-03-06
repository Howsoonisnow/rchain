package coop.rchain.node

import io.grpc.{Server, ServerBuilder}
import scala.concurrent.{ExecutionContext, Future}
import cats._, cats.data._, cats.implicits._
import coop.rchain.node.repl._
import coop.rchain.rholang.interpreter.{RholangCLI, Runtime}
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import monix.execution.Scheduler

import java.io.{Reader, StringReader}

class GrpcServer(executionContext: ExecutionContext, port: Int, runtime: Runtime) { self =>

  var server: Option[Server] = None

  def start(): Unit = {
    server = ServerBuilder
      .forPort(port)
      .addService(ReplGrpc.bindService(new ReplImpl(runtime), executionContext))
      .build
      .start
      .some

    println("Server started, listening on " + port)
    sys.addShutdownHook {
      System.err.println("*** shutting down gRPC server since JVM is shutting down")
      self.stop()
      System.err.println("*** server shut down")
    }
  }

  def stop(): Unit =
    server.foreach(_.shutdown())

  class ReplImpl(runtime: Runtime) extends ReplGrpc.Repl {
    import RholangCLI._
    // TODO we need to handle this better
    import monix.execution.Scheduler.Implicits.global

    def exec(reader: Reader): Future[ReplResponse] = buildNormalizedTerm(reader) match {
      case Left(er) =>
        Future.successful(ReplResponse(s"Error: $er"))
      case Right(term) =>
        evaluate(runtime.reducer, term).attempt
          .map {
            case Left(ex) => s"Caught boxed exception: $ex"
            case Right(_) => s"Storage Contents:\n ${StoragePrinter.prettyPrint(runtime.store)}"
          }
          .map(ReplResponse(_))
          .runAsync
    }

    def run(request: CmdRequest): Future[ReplResponse] =
      exec(new StringReader(request.line))

    def eval(request: EvalRequest): Future[ReplResponse] =
      exec(RholangCLI.reader(request.fileName))
  }
}
