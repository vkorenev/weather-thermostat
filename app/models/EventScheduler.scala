package models

import java.time.{Clock, Instant}
import javax.inject.Inject

import akka.actor.ActorSystem
import models.AppLogic.isAtHome
import models.google.calendar.{GoogleApi, TimedEvent}
import models.nest.NestApi
import play.api.Logger
import utils.JavaConversions.instantOrdering._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class EventScheduler @Inject() (
  system: ActorSystem,
  clock: Clock,
  nestApi: NestApi,
  googleApi: GoogleApi)(implicit exec: ExecutionContext) {
  private[models] val etaWindowBeginsBeforeEventStart = java.time.Duration.ofMinutes(15)
  private[models] val etaWindowEndsBeforeEventStart = java.time.Duration.ofMinutes(5)
  private[models] val upcomingEventsWindowBeginsIn = java.time.Duration.ofMinutes(5)
  private[models] val upcomingEventsWindowEndsIn = java.time.Duration.ofHours(2)

  private val logger: Logger = Logger(this.getClass)

  private[models] def getUpcomingEventsAtHome(accessToken: String, calendarId: String): Future[Seq[TimedEvent]] = {
    val now = Instant.now(clock)
    val intervalStart = now.plus(upcomingEventsWindowBeginsIn)
    val intervalEnd = now.plus(upcomingEventsWindowEndsIn)

    googleApi.getEvents(accessToken)(calendarId, Some(intervalStart), Some(intervalEnd)) map { events =>
      for {
        event <- events.collect { case ev: TimedEvent => ev }
        if event.start.toInstant >= intervalStart && isAtHome(event)
      } yield event
    }
  }

  private[models] def updateETA(accessToken: String, structureId: String, event: TimedEvent): Future[_] = {
    nestApi.withNest(accessToken) { rootRef =>
      val nowPlus1Minute = Instant.now(clock).plus(java.time.Duration.ofMinutes(1))
      val eventStart = event.start.toInstant
      val windowBegin = max(nowPlus1Minute, eventStart.minus(etaWindowBeginsBeforeEventStart))
      val windowEnd = max(nowPlus1Minute, eventStart.minus(etaWindowEndsBeforeEventStart))
      logger.debug(s"Setting ETA for structure $structureId from $windowBegin to $windowEnd because of $event")
      nestApi.updateETA(rootRef, structureId, event.id, windowBegin, windowEnd)
    }
  }

  private def updateETAForUpcomingEvents(googleAccessToken: String, calendarId: String,
    nestAccessToken: String, structureId: String): Future[Int] = {
    for {
      events <- getUpcomingEventsAtHome(googleAccessToken, calendarId)
      results <- Future.sequence(events map { event =>
        updateETA(nestAccessToken, structureId, event)
      })
    } yield results.size
  }

  def scheduleCheckUpcomingEvents(googleAccessToken: String, calendarId: String,
    nestAccessToken: String, structureId: String) = {
    system.scheduler.schedule(0.seconds, 1.minute) {
      updateETAForUpcomingEvents(googleAccessToken, calendarId, nestAccessToken, structureId) onComplete {
        case Success(result) => logger.debug(s"ETA was reported for $result events")
        case Failure(error) => logger.warn("Error updating ETA", error)
      }
    }
  }
}