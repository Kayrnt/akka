/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.Future
import scala.reflect.classTag
import akka.pattern.ask
import java.io.File
import java.security.{ NoSuchAlgorithmException, SecureRandom, PrivilegedAction, AccessController }
import javax.net.ssl.SSLException
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.event.{ Logging, NoLogging, LoggingAdapter }
import akka.remote.transport.netty.{ SSLSettings, NettySSLSupport }

object Configuration {
  // set this in your JAVA_OPTS to see all ssl debug info: "-Djavax.net.debug=ssl,keymanager"
  // The certificate will expire in 2109
  private val trustStore = getClass.getClassLoader.getResource("truststore").getPath
  private val keyStore = getClass.getClassLoader.getResource("keystore").getPath
  private val conf = """
    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"
      test {
        single-expect-default = 10s
        filter-leeway = 10s
        default-timeout = 10s
      }

      remote.enabled-transports = ["akka.remote.netty.ssl"]

      remote.netty.ssl {
        hostname = localhost
        port = %d
        security {
          enable = on
          trust-store = "%s"
          key-store = "%s"
          key-store-password = "changeme"
          key-password = "changeme"
          trust-store-password = "changeme"
          protocol = "TLSv1"
          random-number-generator = "%s"
          enabled-algorithms = [%s]
        }
      }
    }
                     """

  case class CipherConfig(runTest: Boolean, config: Config, cipher: String, localPort: Int, remotePort: Int)

  def getCipherConfig(cipher: String, enabled: String*): CipherConfig = {
    val localPort, remotePort = { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }
    try {
      //if (true) throw new IllegalArgumentException("Ticket1978*Spec isn't enabled")

      val config = ConfigFactory.parseString(conf.format(localPort, trustStore, keyStore, cipher, enabled.mkString(", ")))
      val fullConfig = config.withFallback(AkkaSpec.testConf).withFallback(ConfigFactory.load).getConfig("akka.remote.netty.ssl.security")
      val settings = new SSLSettings(fullConfig)

      val rng = NettySSLSupport.initializeCustomSecureRandom(settings.SSLRandomNumberGenerator, NoLogging)

      rng.nextInt() // Has to work
      settings.SSLRandomNumberGenerator foreach {
        sRng ⇒ rng.getAlgorithm == sRng || (throw new NoSuchAlgorithmException(sRng))
      }

      val engine = NettySSLSupport.initializeClientSSL(settings, NoLogging).getEngine
      val gotAllSupported = enabled.toSet -- engine.getSupportedCipherSuites.toSet
      val gotAllEnabled = enabled.toSet -- engine.getEnabledCipherSuites.toSet
      gotAllSupported.isEmpty || (throw new IllegalArgumentException("Cipher Suite not supported: " + gotAllSupported))
      gotAllEnabled.isEmpty || (throw new IllegalArgumentException("Cipher Suite not enabled: " + gotAllEnabled))
      engine.getSupportedProtocols.contains(settings.SSLProtocol.get) ||
        (throw new IllegalArgumentException("Protocol not supported: " + settings.SSLProtocol.get))

      CipherConfig(true, config, cipher, localPort, remotePort)
    } catch {
      case (_: IllegalArgumentException) | (_: NoSuchAlgorithmException) ⇒ CipherConfig(false, AkkaSpec.testConf, cipher, localPort, remotePort) // Cannot match against the message since the message might be localized :S
    }
  }
}

import Configuration.{ CipherConfig, getCipherConfig }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978SHA1PRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("SHA1PRNG", "TLS_RSA_WITH_AES_128_CBC_SHA"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES128CounterSecureRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES128CounterSecureRNG", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES256CounterSecureRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES256CounterSecureRNG", "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))

/**
 * Both of the <quote>Inet</quote> variants require access to the Internet to access random.org.
 */
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES128CounterInetRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES128CounterInetRNG", "TLS_RSA_WITH_AES_128_CBC_SHA"))

/**
 * Both of the <quote>Inet</quote> variants require access to the Internet to access random.org.
 */
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978AES256CounterInetRNGSpec extends Ticket1978CommunicationSpec(getCipherConfig("AES256CounterInetRNG", "TLS_RSA_WITH_AES_256_CBC_SHA"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978DefaultRNGSecureSpec extends Ticket1978CommunicationSpec(getCipherConfig("", "TLS_RSA_WITH_AES_128_CBC_SHA"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978CrappyRSAWithMD5OnlyHereToMakeSureThingsWorkSpec extends Ticket1978CommunicationSpec(getCipherConfig("", "SSL_RSA_WITH_NULL_MD5"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket1978NonExistingRNGSecureSpec extends Ticket1978CommunicationSpec(CipherConfig(false, AkkaSpec.testConf, "NonExistingRNG", 12345, 12346))

abstract class Ticket1978CommunicationSpec(val cipherConfig: CipherConfig) extends AkkaSpec(cipherConfig.config) with ImplicitSender {

  implicit val timeout: Timeout = Timeout(10 seconds)

  lazy val other: ActorSystem = ActorSystem(
    "remote-sys",
    ConfigFactory.parseString("akka.remote.netty.ssl.port = " + cipherConfig.remotePort).withFallback(system.settings.config))

  override def afterTermination() {
    if (cipherConfig.runTest) {
      shutdown(other)
    }
  }

  ("-") must {
    if (cipherConfig.runTest) {
      val ignoreMe = other.actorOf(Props(new Actor { def receive = { case ("ping", x) ⇒ sender() ! ((("pong", x), sender())) } }), "echo")
      val otherAddress = other.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.defaultAddress

      "support tell" in {
        val here = {
          system.actorSelection(otherAddress.toString + "/user/echo") ! Identify(None)
          expectMsgType[ActorIdentity].ref.get
        }

        for (i ← 1 to 1000) here ! (("ping", i))
        for (i ← 1 to 1000) expectMsgPF(timeout.duration) { case (("pong", i), `testActor`) ⇒ true }
      }

      "support ask" in {
        import system.dispatcher
        val here = {
          system.actorSelection(otherAddress.toString + "/user/echo") ! Identify(None)
          expectMsgType[ActorIdentity].ref.get
        }

        val f = for (i ← 1 to 1000) yield here ? (("ping", i)) mapTo classTag[((String, Int), ActorRef)]
        Await.result(Future.sequence(f), timeout.duration).map(_._1._1).toSet should be(Set("pong"))
      }

    } else {
      "not be run when the cipher is not supported by the platform this test is currently being executed on" in {
        pending
      }
    }

  }

}
