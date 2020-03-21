package freya.watcher

import cats.Parallel
import cats.effect.ConcurrentEffect
import cats.effect.concurrent.MVar
import cats.implicits._
import com.typesafe.scalalogging.StrictLogging
import freya.ExitCodes.ConsumerExitCode
import freya.errors.OperatorError
import freya.watcher.actions.ServerAction

import scala.collection.mutable

class Channels[F[_]: Parallel, T, U](
  newActionConsumer: (String, MVar[F, Unit], Option[FeedbackConsumerAlg[F, U]]) => ActionConsumer[F, T, U],
  newFeedbackConsumer: () => Option[FeedbackConsumerAlg[F, U]]
)(implicit F: ConcurrentEffect[F])
    extends StrictLogging {

  private val actionConsumers = mutable.HashMap.empty[String, ActionConsumer[F, T, U]]

  private[freya] def getConsumer(namespace: String): Option[ActionConsumer[F, T, U]] =
    actionConsumers.get(namespace)

  private[freya] def registerConsumer(namespace: String): F[(ActionConsumer[F, T, U], F[ConsumerExitCode])] =
    for {
      feedbackConsumer <- newFeedbackConsumer().pure[F]
      notifyFlag <- MVar.empty[F, Unit]
      consumer = newActionConsumer(namespace, notifyFlag, feedbackConsumer)
      _ = actionConsumers += namespace -> consumer
      startConsumer <- F.delay {
        val start = feedbackConsumer match {
          case Some(feedback) => F.race(consumer.consume, feedback.consume).map(_.merge)
          case None => consumer.consume
        }
        F.delay(logger.info(s"Action consumer for '$namespace' namespace was started")) *> start
      }
    } yield (consumer, startConsumer)

  private[freya] def putForAll(action: Either[OperatorError, ServerAction[T, U]]): F[Unit] =
    actionConsumers.values.map(_.putAction(action)).toList.parSequence.void
}
