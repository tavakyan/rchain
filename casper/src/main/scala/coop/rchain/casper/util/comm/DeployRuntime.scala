package coop.rchain.casper.util.comm

import cats.{Id, Monad, MonadError}
import cats.effect.{Sync, Timer}
import cats.implicits._
import coop.rchain.casper.protocol
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.comm.ListenAtName._
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.catscontrib._
import coop.rchain.models.Channel.ChannelInstance
import coop.rchain.models.{Channel, Par}

import scala.io.Source
import scala.language.higherKinds
import scala.util._

object DeployRuntime {

  type ErrorHandler[F[_]] = ApplicativeError_[F, Throwable]

  def propose[F[_]: Monad: ErrorHandler: Capture: DeployService](): F[Unit] =
    gracefulExit(
      for {
        response <- DeployService[F].createBlock()
        _        <- Capture[F].capture { println(s"Response: ${response._2}") }
      } yield ()
    )

  def showBlock[F[_]: Monad: ErrorHandler: Capture: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(BlockQuery(hash)).map(println(_)))

  def showMainChain[F[_]: Monad: ErrorHandler: Capture: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showMainChain(BlocksQuery(depth)).map(println(_)))

  def listenForDataAtName[F[_]: Sync: DeployService: Timer: Capture](
      depth: Int,
      name: Id[Name]
  ): F[Unit] =
    gracefulExit {
      listenAtNameUntilChanges(name) { par: Par =>
        val channel = Channel(ChannelInstance.Quote(par))
        val request = DataAtNameQuery(depth, Some(channel))
        DeployService[F].listenForDataAtName(request) map (_.blockResults)
      }
    }

  def listenForContinuationAtName[F[_]: Sync: Timer: DeployService: Capture](
      depth: Int,
      names: List[Name]
  ): F[Unit] =
    gracefulExit {
      listenAtNameUntilChanges(names) { pars: List[Par] =>
        val channels = pars.map(par => Channel(ChannelInstance.Quote(par)))
        val request  = ContinuationAtNameQuery(depth, channels)
        DeployService[F].listenForContinuationAtName(request) map (_.blockResults)
      }
    }

  //Accepts a Rholang source file and deploys it to Casper
  def deployFileProgram[F[_]: Monad: ErrorHandler: Capture: DeployService](
      purseAddress: String,
      phloLimit: PhloLimit,
      phloPrice: PhloPrice,
      nonce: Int,
      file: String
  ): F[Unit] =
    Try(Source.fromFile(file).mkString) match {
      case Success(code) =>
        gracefulExit(
          for {
            timestamp <- Capture[F].capture { System.currentTimeMillis() }
            //TODO: allow user to specify their public key
            d = DeployData()
              .withTimestamp(timestamp)
              .withTerm(code)
              .withFrom(purseAddress)
              .withPhloLimit(phloLimit)
              .withPhloPrice(phloPrice)
              .withNonce(nonce)
            response <- DeployService[F].deploy(d)
            _ <- Capture[F].capture {
                  println(s"Response: ${response._2}")
                }
          } yield ()
        )

      case Failure(ex) =>
        Capture[F].capture { println(s"Error with given file: \n${ex.getMessage()}") }
    }

  //Simulates user requests by randomly deploying things to Casper.
  def deployDemoProgram[F[_]: Monad: ErrorHandler: Capture: DeployService]: F[Unit] =
    gracefulExit(MonadOps.forever(singleDeploy[F]))

  private def singleDeploy[F[_]: Monad: Capture: DeployService]: F[Unit] =
    for {
      id <- Capture[F].capture { scala.util.Random.nextInt(100) }
      d  = ProtoUtil.basicDeployData(id)
      _ <- Capture[F].capture {
            println(s"Sending the following to Casper: ${d.term}")
          }
      response <- DeployService[F].deploy(d)
      _ <- Capture[F].capture {
            println(s"Response: ${response._2}")
          }
      _ <- IOUtil.sleep[F](4000L)
    } yield ()

  private def gracefulExit[F[_]: Monad: ErrorHandler: Capture, A](program: F[A]): F[Unit] =
    for {
      result <- program.attempt
      _ <- result match {
            case Left(ex) => Capture[F].capture(println(s"Error: ${processError(ex).getMessage}"))
            case _        => ().pure[F]
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

}
