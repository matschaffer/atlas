/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.standalone

import java.io.File

import akka.actor.Props
import com.google.inject.AbstractModule
import com.google.inject.Module
import com.google.inject.multibindings.Multibinder
import com.google.inject.util.Modules
import com.netflix.atlas.akka.WebServer
import com.netflix.atlas.config.ConfigManager
import com.netflix.atlas.core.db.MemoryDatabase
import com.netflix.atlas.webapi.ApiSettings
import com.netflix.atlas.webapi.LocalDatabaseActor
import com.netflix.atlas.webapi.LocalPublishActor
import com.netflix.iep.guice.Governator
import com.netflix.iep.service.Service
import com.netflix.spectator.api.Spectator
import com.netflix.spectator.log4j.SpectatorAppender
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

/**
 * Provides a simple way to start up a standalone server. Usage:
 *
 * ```
 * $ java -jar atlas.jar config1.conf config2.conf
 * ```
 */
object Main extends StrictLogging {

  private def loadAdditionalConfigFiles(files: Array[String]): Unit = {
    files.foreach { f =>
      logger.info(s"loading config file: $f")
      val c = ConfigFactory.parseFileAnySyntax(new File(f))
      ConfigManager.update(c)
    }
  }

  def main(args: Array[String]) {
    SpectatorAppender.addToRootLogger(Spectator.registry(), "spectator", false)
    loadAdditionalConfigFiles(args)

    val serviceModule = new AbstractModule {
      override def configure(): Unit = {
        Governator.getModulesUsingServiceLoader.forEach(install)

        val server = new WebServer("atlas", ApiSettings.port) {
          override protected def configure(): Unit = {
            val db = ApiSettings.newDbInstance
            actorSystem.actorOf(Props(new LocalDatabaseActor(db)), "db")
            db match {
              case mem: MemoryDatabase =>
                logger.info("enabling local publish to memory database")
                actorSystem.actorOf(Props(new LocalPublishActor(mem)), "publish")
              case _ =>
            }
          }
        }

        val serviceBinder = Multibinder.newSetBinder(binder, classOf[Service])
        serviceBinder.addBinding().toInstance(server)
      }
    }

    val overrides = Modules.`override`(serviceModule).`with`(new AbstractModule {
      override def configure(): Unit = {
        bind(classOf[Config]).toInstance(ConfigManager.current)
      }
    })

    val modules = new java.util.ArrayList[Module]()
    modules.add(overrides)

    val gov = new Governator
    gov.start(modules)
    gov.addShutdownHook()
  }
}
