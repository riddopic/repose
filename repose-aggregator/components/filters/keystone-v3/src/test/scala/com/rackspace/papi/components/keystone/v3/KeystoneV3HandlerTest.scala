package com.rackspace.papi.components.keystone.v3

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.ws.rs.core.MediaType

import com.mockrunner.mock.web.{MockHttpServletRequest, MockHttpServletResponse}
import com.rackspace.papi.commons.util.http.header.HeaderName
import com.rackspace.papi.commons.util.http.{CommonHttpHeader, HttpStatusCode, ServiceClientResponse}
import com.rackspace.papi.commons.util.servlet.http.{MutableHttpServletResponse, ReadableHttpServletResponse}
import com.rackspace.papi.components.datastore.Datastore
import com.rackspace.papi.components.keystone.v3.config.{ServiceEndpoint, KeystoneV3Config, OpenstackKeystoneService, WhiteList}
import com.rackspace.papi.components.keystone.v3.objects._
import com.rackspace.papi.components.keystone.v3.utilities.KeystoneV3Headers
import com.rackspace.papi.components.keystone.v3.utilities.exceptions.InvalidAdminCredentialsException
import com.rackspace.papi.filter.logic.{FilterAction, FilterDirector, HeaderManager}
import com.rackspace.papi.service.datastore.DatastoreService
import com.rackspace.papi.service.serviceclient.akka.AkkaServiceClient
import org.apache.http.message.BasicHeader
import org.hamcrest.Matchers.{equalTo, lessThanOrEqualTo}
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.mockito.Matchers.{any, anyMap, anyString, argThat, contains, intThat}
import org.mockito.Mockito.{verify, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSpec, Matchers, PrivateMethodTester}

import scala.util.{Failure, Success, Try}

@RunWith(classOf[JUnitRunner])
class KeystoneV3HandlerTest extends FunSpec with BeforeAndAfter with Matchers with PrivateMethodTester with MockitoSugar {

  var keystoneV3Handler: KeystoneV3Handler = _
  var keystoneConfig: KeystoneV3Config = _
  var mockAkkaServiceClient: AkkaServiceClient = _
  var mockDatastoreService: DatastoreService = _
  var mockDatastore: Datastore = _

  before {
    mockAkkaServiceClient = mock[AkkaServiceClient]
    mockDatastoreService = mock[DatastoreService]
    mockDatastore = mock[Datastore]
    keystoneConfig = new KeystoneV3Config()
    keystoneConfig.setKeystoneService(new OpenstackKeystoneService())
    keystoneConfig.getKeystoneService.setUsername("user")
    keystoneConfig.getKeystoneService.setPassword("password")
    keystoneConfig.getKeystoneService.setUri("http://test-uri.com")

    when(mockDatastoreService.getDefaultDatastore).thenReturn(mockDatastore)
    when(mockDatastore.get(anyString)).thenReturn(null, Nil: _*)

    keystoneV3Handler = new KeystoneV3Handler(keystoneConfig, mockAkkaServiceClient, mockDatastoreService)
  }

  describe("handleRequest") {
    val mockServletResponse = mock[ReadableHttpServletResponse]

    it("should pass filter if uri is in the whitelist") {
      val whiteList = new WhiteList()
      whiteList.getUriPattern.add("/test1")
      whiteList.getUriPattern.add("/test2")
      keystoneConfig.setWhiteList(whiteList)

      val mockRequest = new MockHttpServletRequest()
      mockRequest.setRequestURI("/test1")

      keystoneV3Handler.handleRequest(mockRequest, mockServletResponse).getFilterAction should be theSameInstanceAs FilterAction.PASS
    }

    it("should attempt validation if uri isn't in the whitelist") {
      val whiteList = new WhiteList()
      whiteList.getUriPattern.add("/test1")
      whiteList.getUriPattern.add("/test2")
      keystoneConfig.setWhiteList(whiteList)

      val mockRequest = new MockHttpServletRequest()
      mockRequest.setRequestURI("/test3")

      keystoneV3Handler.handleRequest(mockRequest, mockServletResponse).getFilterAction should be theSameInstanceAs FilterAction.RETURN
      keystoneV3Handler.handleRequest(mockRequest, mockServletResponse).getResponseStatus should be theSameInstanceAs HttpStatusCode.UNAUTHORIZED
    }
  }

  describe("handleResponse") {
    // TODO: Get this to work, or make it a system level test
    ignore("should set the appropriate response status") {
      val mockServletRequest = new MockHttpServletRequest()
      val mockServletResponse = new MockHttpServletResponse()

      val responseStatus = "response-status-key"
      val responseWwwAuthenticate = "response-www-authenticate"
      val resultStatus = "result-status"
      val resultWwwAuthenticate = "result-www-authenticate"

      List(
        Map(
          responseStatus -> HttpStatusCode.OK,
          resultStatus -> HttpStatusCode.OK
        ),
        Map(
          responseStatus -> HttpStatusCode.FORBIDDEN,
          responseWwwAuthenticate -> KeystoneV3Headers.X_DELEGATED,
          resultStatus -> HttpStatusCode.FORBIDDEN,
          resultWwwAuthenticate -> "Keystone uri=http://test-uri.com"
        ),
        Map(
          responseStatus -> HttpStatusCode.UNAUTHORIZED,
          responseWwwAuthenticate -> KeystoneV3Headers.X_DELEGATED,
          resultStatus -> HttpStatusCode.FORBIDDEN,
          resultWwwAuthenticate -> "Keystone uri=http://test-uri.com"
        ),
        Map(
          responseStatus -> HttpStatusCode.UNAUTHORIZED,
          resultStatus -> HttpStatusCode.INTERNAL_SERVER_ERROR
        ),
        Map(
          responseStatus -> HttpStatusCode.NOT_IMPLEMENTED,
          responseWwwAuthenticate -> KeystoneV3Headers.X_DELEGATED,
          resultStatus -> HttpStatusCode.INTERNAL_SERVER_ERROR
        ),
        Map(
          responseStatus -> HttpStatusCode.NOT_IMPLEMENTED,
          resultStatus -> HttpStatusCode.NOT_IMPLEMENTED
        )
      ).map { parameterMap =>
        mockServletResponse.setStatus(parameterMap.get(responseStatus).get.asInstanceOf[HttpStatusCode].intValue)
        if (parameterMap.get(responseWwwAuthenticate).isDefined) {
          mockServletResponse.addHeader(CommonHttpHeader.WWW_AUTHENTICATE.toString, parameterMap.get(responseWwwAuthenticate).get.asInstanceOf[String])
        }

        val responseFilterDirector = keystoneV3Handler.handleResponse(mockServletRequest, MutableHttpServletResponse.wrap(mockServletRequest, mockServletResponse))

        responseFilterDirector.getResponseStatus shouldBe parameterMap.get(resultStatus).get
        if (parameterMap.get(resultWwwAuthenticate).isDefined) {
          responseFilterDirector.responseHeaderManager().headersToAdd().get(HeaderName.wrap(CommonHttpHeader.WWW_AUTHENTICATE.toString)) should contain(parameterMap.get(resultWwwAuthenticate).get)
        }
      }
    }
  }

  describe("authenticate") {
    val authenticate = PrivateMethod[FilterDirector]('authenticate)

    it("should return unauthorized when the x-auth-token header is not present") {
      val mockRequest = new MockHttpServletRequest()

      val filterDirector = keystoneV3Handler invokePrivate authenticate(mockRequest)

      filterDirector.getResponseStatus should be(HttpStatusCode.UNAUTHORIZED)
      filterDirector.getFilterAction should be(FilterAction.RETURN)
    }
  }

  describe("validateSubjectToken") {
    val validateSubjectToken = PrivateMethod[Try[_]]('validateSubjectToken)

    it("should return a Failure when x-subject-token validation fails") {
      val mockGetServiceClientResponse = mock[ServiceClientResponse]

      keystoneV3Handler.cachedAdminToken = "test-admin-token"

      when(mockGetServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.NOT_FOUND.intValue)
      when(mockAkkaServiceClient.get(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]])).thenReturn(mockGetServiceClientResponse)

      keystoneV3Handler invokePrivate validateSubjectToken("test-subject-token", false) shouldBe a[Failure[_]]
    }

    it("should return a Success for a cached admin token") {
      when(mockDatastore.get(anyString)).thenReturn(AuthenticateResponse(null, null, null, null, null, null, null, null), Nil: _*)

      keystoneV3Handler invokePrivate validateSubjectToken("test-subject-token", false) shouldBe a[Success[_]]
      keystoneV3Handler.invokePrivate(validateSubjectToken("test-subject-token", false)).get shouldBe an[AuthenticateResponse]
    }

    it("should return a token object when x-subject-token validation succeeds") {
      val mockGetServiceClientResponse = mock[ServiceClientResponse]

      keystoneV3Handler.cachedAdminToken = "test-admin-token"

      when(mockGetServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.OK.intValue)
      when(mockGetServiceClientResponse.getData).thenReturn(new ByteArrayInputStream(
        "{\"token\":{\"expires_at\":\"2013-02-27T18:30:59.999999Z\",\"issued_at\":\"2013-02-27T16:30:59.999999Z\",\"methods\":[\"password\"],\"user\":{\"domain\":{\"id\":\"1789d1\",\"links\":{\"self\":\"http://identity:35357/v3/domains/1789d1\"},\"name\":\"example.com\"},\"id\":\"0ca8f6\",\"links\":{\"self\":\"http://identity:35357/v3/users/0ca8f6\"},\"name\":\"Joe\"}}}"
          .getBytes))
      when(mockAkkaServiceClient.get(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]])).thenReturn(mockGetServiceClientResponse)

      keystoneV3Handler invokePrivate validateSubjectToken("test-subject-token", false) shouldBe a[Success[_]]
      keystoneV3Handler.invokePrivate(validateSubjectToken("test-subject-token", false)).get shouldBe an[AuthenticateResponse]
    }

    it("should cache a token object when x-subject-token validation succeeds with the correct TTL") {
      val mockGetServiceClientResponse = mock[ServiceClientResponse]
      val currentTime = DateTime.now()
      val expirationTime = currentTime.plusMillis(100000)
      val returnJson = "{\"token\":{\"expires_at\":\"" + ISODateTimeFormat.dateTime().print(expirationTime) + "\",\"issued_at\":\"2013-02-27T16:30:59.999999Z\",\"methods\":[\"password\"],\"user\":{\"domain\":{\"id\":\"1789d1\",\"links\":{\"self\":\"http://identity:35357/v3/domains/1789d1\"},\"name\":\"example.com\"},\"id\":\"0ca8f6\",\"links\":{\"self\":\"http://identity:35357/v3/users/0ca8f6\"},\"name\":\"Joe\"}}}"

      keystoneV3Handler.cachedAdminToken = "test-admin-token"

      when(mockGetServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.OK.intValue)
      when(mockGetServiceClientResponse.getData).thenReturn(new ByteArrayInputStream(returnJson.getBytes))
      when(mockAkkaServiceClient.get(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]])).thenReturn(mockGetServiceClientResponse)

      keystoneV3Handler invokePrivate validateSubjectToken("test-subject-token", false)

      verify(mockDatastore).put(argThat(equalTo("TOKEN:test-subject-token")), any[Serializable], intThat(lessThanOrEqualTo((expirationTime.getMillis - currentTime.getMillis).toInt)), any[TimeUnit])
    }
  }

  describe("fetchAdminToken") {
    val fetchAdminToken = PrivateMethod[Try[String]]('fetchAdminToken)

    it("should build a JSON auth token request without a domain ID") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.UNAUTHORIZED.intValue)
      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      keystoneV3Handler invokePrivate fetchAdminToken(false)

      verify(mockAkkaServiceClient).post(
        anyString,
        anyString,
        anyMap.asInstanceOf[java.util.Map[String, String]],
        contains("{\"auth\":{\"identity\":{\"methods\":[\"password\"],\"password\":{\"user\":{\"name\":\"user\",\"password\":\"password\"}}}}}"),
        any[MediaType],
        any[MediaType]
      )
    }

    it("should build a JSON auth token request with a string domain ID") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      keystoneConfig.getKeystoneService.setProjectId("projectId")

      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.UNAUTHORIZED.intValue)
      keystoneV3Handler invokePrivate fetchAdminToken(false)

      verify(mockAkkaServiceClient).post(
        anyString,
        anyString,
        anyMap.asInstanceOf[java.util.Map[String, String]],
        contains("{\"auth\":{\"identity\":{\"methods\":[\"password\"],\"password\":{\"user\":{\"name\":\"user\",\"password\":\"password\"}}},\"scope\":{\"project\":{\"id\":\"projectId\"}}}}"),
        any[MediaType],
        any[MediaType]
      )
    }

    it("should return a Failure when unable to retrieve admin token") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.UNAUTHORIZED.intValue)
      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      keystoneV3Handler invokePrivate fetchAdminToken(false) shouldBe a[Failure[_]]
      keystoneV3Handler.invokePrivate(fetchAdminToken(false)).failed.get shouldBe a[InvalidAdminCredentialsException]
    }

    it("should return a Success for a cached admin token") {
      keystoneV3Handler.cachedAdminToken = "test-cached-token"

      keystoneV3Handler invokePrivate fetchAdminToken(false) shouldBe a[Success[_]]
      keystoneV3Handler.invokePrivate(fetchAdminToken(false)).get should startWith("test-cached-token")
    }

    it("should return an admin token as a string when the admin API call succeeds") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.CREATED.intValue)
      when(mockServiceClientResponse.getHeaders).thenReturn(Array(new BasicHeader(KeystoneV3Headers.X_SUBJECT_TOKEN, "test-admin-token")), Nil: _*)
      when(mockServiceClientResponse.getData).thenReturn(new ByteArrayInputStream("{\"token\":{\"expires_at\":\"2013-02-27T18:30:59.999999Z\",\"issued_at\":\"2013-02-27T16:30:59.999999Z\",\"methods\":[\"password\"],\"user\":{\"domain\":{\"id\":\"1789d1\",\"links\":{\"self\":\"http://identity:35357/v3/domains/1789d1\"},\"name\":\"example.com\"},\"id\":\"0ca8f6\",\"links\":{\"self\":\"http://identity:35357/v3/users/0ca8f6\"},\"name\":\"Joe\"}}}".getBytes))
      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      keystoneV3Handler invokePrivate fetchAdminToken(false) shouldBe a[Success[_]]
      keystoneV3Handler.invokePrivate(fetchAdminToken(false)).get should startWith("test-admin-token")
    }

    it("should return a new admin token (non-cached) if force is set to true") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      when(mockDatastore.get(anyString)).thenReturn("test-cached-token", Nil: _*)
      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.CREATED.intValue)
      when(mockServiceClientResponse.getHeaders).thenReturn(Array(new BasicHeader(KeystoneV3Headers.X_SUBJECT_TOKEN, "test-admin-token")), Nil: _*)
      when(mockServiceClientResponse.getData).thenReturn(new ByteArrayInputStream("{\"token\":{\"expires_at\":\"2013-02-27T18:30:59.999999Z\",\"issued_at\":\"2013-02-27T16:30:59.999999Z\",\"methods\":[\"password\"],\"user\":{\"domain\":{\"id\":\"1789d1\",\"links\":{\"self\":\"http://identity:35357/v3/domains/1789d1\"},\"name\":\"example.com\"},\"id\":\"0ca8f6\",\"links\":{\"self\":\"http://identity:35357/v3/users/0ca8f6\"},\"name\":\"Joe\"}}}".getBytes))
      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      keystoneV3Handler invokePrivate fetchAdminToken(true) shouldBe a[Success[_]]
      keystoneV3Handler.invokePrivate(fetchAdminToken(true)).get should startWith("test-admin-token")
    }

    it("should cache an admin token when the admin API call succeeds") {
      val mockServiceClientResponse = mock[ServiceClientResponse]

      keystoneV3Handler.cachedAdminToken = null

      when(mockServiceClientResponse.getStatusCode).thenReturn(HttpStatusCode.CREATED.intValue)
      when(mockServiceClientResponse.getHeaders).thenReturn(Array(new BasicHeader(KeystoneV3Headers.X_SUBJECT_TOKEN, "test-admin-token")), Nil: _*)
      when(mockServiceClientResponse.getData).thenReturn(new ByteArrayInputStream("{\"token\":{\"expires_at\":\"2013-02-27T18:30:59.999999Z\",\"issued_at\":\"2013-02-27T16:30:59.999999Z\",\"methods\":[\"password\"],\"user\":{\"domain\":{\"id\":\"1789d1\",\"links\":{\"self\":\"http://identity:35357/v3/domains/1789d1\"},\"name\":\"example.com\"},\"id\":\"0ca8f6\",\"links\":{\"self\":\"http://identity:35357/v3/users/0ca8f6\"},\"name\":\"Joe\"}}}".getBytes))
      when(mockAkkaServiceClient.post(anyString, anyString, anyMap.asInstanceOf[java.util.Map[String, String]], anyString, any(classOf[MediaType]), any(classOf[MediaType]))).
        thenReturn(mockServiceClientResponse, Nil: _*) // Note: Nil was passed to resolve the ambiguity between Mockito's multiple method signatures

      keystoneV3Handler invokePrivate fetchAdminToken(false)

      keystoneV3Handler.cachedAdminToken should startWith("test-admin-token")
    }
  }

  describe("writeProjectHeader") {
    val writeProjectHeader = PrivateMethod[Unit]('writeProjectHeader)
    val filterDirector = mock[FilterDirector]
    val headerManager = mock[HeaderManager]
    when(filterDirector.requestHeaderManager()).thenReturn(headerManager)
    val roles = List(Role(null, null, Option("12345"), null, null), Role(null, null, Option("67890"), null, null))

    it("should only provide the url project when the flag says to not write all") {
      keystoneV3Handler invokePrivate writeProjectHeader("abcde", roles, false, filterDirector)
      verify(headerManager).appendHeader(org.mockito.Matchers.eq("X-PROJECT-ID"), org.mockito.Matchers.eq("abcde"))
    }

    it("should provide all the projects when the flag says to write all") {
      keystoneV3Handler invokePrivate writeProjectHeader("abcde", roles, true, filterDirector)
      verify(headerManager).appendHeader(org.mockito.Matchers.eq("X-PROJECT-ID"), org.mockito.Matchers.eq("12345"), org.mockito.Matchers.eq("67890"), org.mockito.Matchers.eq("abcde"))
    }
  }

  describe("containsEndpoint") {
    val containsEndpoint = PrivateMethod[Boolean]('containsEndpoint)

    it("should return true when there is an endpoint that matches the url") {
      keystoneConfig.setServiceEndpoint(new ServiceEndpoint)
      keystoneConfig.getServiceEndpoint.setUrl("http://www.notreallyawebsite.com")
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, null, "http://www.woot.com"), Endpoint(null, null, null, null, "http://www.notreallyawebsite.com"))) should be(true)
    }

    it("should return false when there isn't an endpoint that matches the url") {
      keystoneConfig.setServiceEndpoint(new ServiceEndpoint)
      keystoneConfig.getServiceEndpoint.setUrl("http://www.notreallyawebsite.com")
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, null, "http://www.woot.com"), Endpoint(null, null, null, null, "http://www.banana.com"))) should be(false)
    }

    it("Should return true when the url matches and region does") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setRegion("DFW")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, None, "http://www.woot.com"), Endpoint(null, null, null, Option("DFW"), "http://www.notreallyawebsite.com"))) should be(true)
    }

    it("Should return false when the url matches and region doesn't") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setRegion("DFW")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, None, "http://www.woot.com"), Endpoint(null, null, null, None, "http://www.notreallyawebsite.com"))) should be(false)
    }

    it("Should return true when the url matches and name does") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setName("foo")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, None, "http://www.woot.com"), Endpoint(null, "foo", null, Option("DFW"), "http://www.notreallyawebsite.com"))) should be(true)
    }

    it("Should return false when the url matches and name doesn't") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setName("foo")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, null, None, "http://www.woot.com"), Endpoint(null, "bar", null, None, "http://www.notreallyawebsite.com"))) should be(false)
    }

    it("Should return true when the url matches and interface does") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setInterface("foo")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, None, None, "http://www.woot.com"), Endpoint(null, "foo", Option("foo"), Option("DFW"), "http://www.notreallyawebsite.com"))) should be(true)
    }

    it("Should return false when the url matches and interface doesn't") {
      val serviceEndpoint = new ServiceEndpoint
      serviceEndpoint.setUrl("http://www.notreallyawebsite.com")
      serviceEndpoint.setInterface("foo")
      keystoneConfig.setServiceEndpoint(serviceEndpoint)
      keystoneV3Handler invokePrivate containsEndpoint(List(Endpoint(null, null, None, None, "http://www.woot.com"), Endpoint(null, "bar", None, None, "http://www.notreallyawebsite.com"))) should be(false)
    }
  }

  describe("offsetTtl") {
    it("should return the configured ttl is offset is 0") {
      keystoneV3Handler.offsetTtl(1000, 0) shouldBe 1000
    }

    it("should return 0 if the configured ttl is 0") {
      keystoneV3Handler.offsetTtl(0, 1000) shouldBe 0
    }

    it("should return a random int between configured ttl +/- offset") {
      val firstCall = keystoneV3Handler.offsetTtl(1000, 100)
      val secondCall = keystoneV3Handler.offsetTtl(1000, 100)

      firstCall shouldBe 1000 +- 1000
      secondCall shouldBe 1000 +- 1000
      firstCall should not be secondCall
    }
  }
}