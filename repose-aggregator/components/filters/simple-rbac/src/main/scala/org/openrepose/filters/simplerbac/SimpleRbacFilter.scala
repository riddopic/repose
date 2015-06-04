/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.filters.simplerbac

import java.io.{ByteArrayInputStream, File, IOException, InputStream}
import java.net.URL
import java.util
import java.util.UUID
import javax.inject.{Inject, Named}
import javax.servlet._
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import javax.xml.transform.stream.StreamSource

import com.rackspace.com.papi.components.checker.handler._
import com.rackspace.com.papi.components.checker.{Config, Validator}
import com.rackspace.httpdelegation.HttpDelegationManager
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.apache.commons.lang3.StringUtils
import org.openrepose.commons.config.manager.UpdateListener
import org.openrepose.commons.utils.StringUriUtilities
import org.openrepose.commons.utils.servlet.http.{MutableHttpServletRequest, MutableHttpServletResponse}
import org.openrepose.core.filter.FilterConfigHelper
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.filters.simplerbac.config.SimpleRbacConfig

import scala.io.Source
import scala.util.Try

@Named
class SimpleRbacFilter @Inject()(configurationService: ConfigurationService)
  extends Filter
  with UpdateListener[SimpleRbacConfig]
  with HttpDelegationManager
  with LazyLogging {

  private final val DEFAULT_CONFIG = "simple-rbac.cfg.xml"

  var configurationFile: String = DEFAULT_CONFIG
  var configuration: SimpleRbacConfig = _
  var initialized = false
  var validator: Validator = _
  val config = new Config

  override def init(filterConfig: FilterConfig): Unit = {
    configurationFile = new FilterConfigHelper(filterConfig).getFilterConfig(DEFAULT_CONFIG)
    logger.info("Initializing filter using config " + configurationFile)
    val xsdURL: URL = getClass.getResource("/META-INF/schema/config/simple-rbac.xsd")
    configurationService.subscribeTo(
      filterConfig.getFilterName,
      configurationFile,
      xsdURL,
      this,
      classOf[SimpleRbacConfig]
    )
  }

  override def destroy(): Unit = {
    configurationService.unsubscribeFrom(configurationFile, this)
  }

  override def doFilter(servletRequest: ServletRequest, servletResponse: ServletResponse, filterChain: FilterChain): Unit = {
    if (!initialized) {
      logger.error("Simple RBAC filter has not yet initialized...")
      servletResponse.asInstanceOf[HttpServletResponse].sendError(500)
    } else {
      val mutableHttpRequest = MutableHttpServletRequest.wrap(servletRequest.asInstanceOf[HttpServletRequest])
      val mutableHttpResponse = MutableHttpServletResponse.wrap(mutableHttpRequest, servletResponse.asInstanceOf[HttpServletResponse])

      logger.trace("Simple RBAC filter processing request...")
      validator.validate(mutableHttpRequest,mutableHttpResponse,filterChain)
    }
    logger.trace("Simple RBAC filter returning response...")
  }

  override def configurationUpdated(configurationObject: SimpleRbacConfig): Unit = {
    configuration = configurationObject
    val isDelegating = configuration.getDelegating != null
    val delegationQuality = if (isDelegating) configuration.getDelegating.getQuality else 0.0
    config.enableRaxRolesExtension = true
    config.checkPlainParams = true
    config.maskRaxRoles403 = configuration.isEnableMasking403S
    config.setResultHandler(getHandlers(
      isDelegating,
      delegationQuality,
      true,
      "/tmp",
      "simple-rbac.dot"
    ))

    val rbacWadl = rbacToWadl(Option(configuration.getResources)).orElse(
      Option(configuration.getResourcesFileName: String) match {
        case Some(n) =>
          rbacToWadl(readResource(
              configurationService.getResourceResolver.resolve(n).newInputStream()
          ))
        case _ =>
          None
      }
    )
    rbacWadl match {
      case Some(r) =>
        logger.debug(s"Generated WADL:\n\n$r\n")
        val uuid = UUID.randomUUID
        validator = Validator.apply(
          s"SimpleRbacValidator_$uuid",
          new StreamSource(new ByteArrayInputStream(r.getBytes), "file://simple-rbac.wadl"),
          config
        )
        initialized = true
      case _ =>
        logger.warn("Unable to generate the WADL; check the provided resources.")
    }
  }

  override def isInitialized: Boolean = initialized

  private def getHandlers(isDelegating: Boolean,
                          delegationQuality: Double,
                          isEnableApiCoverage: Boolean,
                          configRoot: String,
                          dotOutput: String): DispatchHandler = {
    val handlers: util.List[ResultHandler] = new util.ArrayList[ResultHandler]
    if (isDelegating) {
      handlers.add(new MethodLabelHandler)
      handlers.add(new DelegationHandler(delegationQuality))
    } else {
      handlers.add(new ServletResultHandler)
    }
    if (isEnableApiCoverage) {
      handlers.add(new InstrumentedHandler)
      handlers.add(new ApiCoverageHandler)
    }
    if (StringUtils.isNotBlank(dotOutput)) {
      val dotPath: String = StringUriUtilities.formatUri(getPath(dotOutput, configRoot))
      val out: File = new File(dotPath)
      try {
        if (out.exists && out.canWrite || !out.exists && out.createNewFile) {
          handlers.add(new SaveDotHandler(out, isEnableApiCoverage, true))
        } else {
          logger.warn("Cannot write to DOT file: " + dotPath)
        }
      } catch {
        case ex: IOException =>
          logger.warn("Cannot write to DOT file: " + dotPath, ex)
      }
    }
    new DispatchHandler(handlers.toArray(new Array[ResultHandler](0)))
  }

  private def getPath(path: String, configRoot: String): String = {
    val file: File = new File(path)
    if (file.isAbsolute) {
      file.getAbsolutePath
    } else {
      new File(configRoot, path).getAbsolutePath
    }
  }

  case class Resource(path: String, methods: Set[String], roles: Set[String])

  def readResource(resourceStream: InputStream): Option[String] = {
    Try(Some(Source.fromInputStream(resourceStream).getLines().mkString("\n"))).getOrElse(None)
  }

  private def rbacToWadl(rbac: Option[String]): Option[String] = {
    val parsed = parseResources(rbac)
    parsed match {
      case Some(_) =>
        val header = s"""<application xmlns:rax="http://docs.rackspace.com/api"
                        |         xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                        |         xmlns="http://wadl.dev.java.net/2009/02"
                        |    >
                        |  <resources base="http://localhost">""".stripMargin
        val resources = parsed.getOrElse(List.empty[Resource]).map { r =>
          def toMethods(resource: Resource, uuid: UUID) = {
            val roles = resource.roles.mkString(" ")
            val raxRoles = roles match {
              case a if roles.equalsIgnoreCase("ANY") || roles.equalsIgnoreCase("ALL") => ""
              case _ =>
                s"""rax:roles="$roles""""
            }
            resource.methods.map {
              _ match {
                case a if a.equalsIgnoreCase("ANY") || a.equalsIgnoreCase("ALL") =>
                  s"""      <method name="GET"    id="_$uuid-GET"    $raxRoles/>
                     |      <method name="PUT"    id="_$uuid-PUT"    $raxRoles/>
                     |      <method name="POST"   id="_$uuid-POST"   $raxRoles/>
                     |      <method name="DELETE" id="_$uuid-DELETE" $raxRoles/>""".stripMargin
                case m =>
                  s"""      <method name="$m"   id="_$uuid-$m"    $raxRoles/>"""
              }
            }.mkString("\n")
          }
          val path = r.path
          val uuid = UUID.randomUUID
          val methods = toMethods(r, uuid)
          s"""    <resource id="_$uuid" path=\"$path">
             |$methods
             |    </resource>""".stripMargin
        }.mkString("\n")
        val footer = s"""  </resources>
                        |</application>""".stripMargin

        Some(s"$header\n$resources\n$footer")
      case _ =>
        None
    }
  }

  private def parseResources(lines: Option[String]): Option[List[Resource]] = {
    lines match {
      case Some(l) =>
        Some(l.replaceAll("[\r?\n?]", "\n").split('\n').toList.flatMap(parseLine))
      case _ =>
        None
    }
  }

  private def parseLine(line: String): Option[Resource] = {
    val values = line.split("\\s+")
    if (values.length == 3) {
      Some(new Resource(values(0), parseMethodsRoles(values(1)), parseMethodsRoles(values(2))))
    } else {
      logger.warn(s"Malformed RBAC Resource: $line")
      None
    }
  }

  private def parseMethodsRoles(value: String): Set[String] = {
    Try(value.split(',').toSet[String].map(_.trim)).getOrElse(Set.empty)
  }
}
