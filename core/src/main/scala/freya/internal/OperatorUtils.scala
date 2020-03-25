package freya.internal

import java.net.URL

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.K8sNamespace
import freya.K8sNamespace.{CurrentNamespace, Namespace}
import io.fabric8.kubernetes.client.utils.HttpClientUtils
import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient}
import okhttp3.{HttpUrl, Request}

import scala.util.Try

private[freya] object OperatorUtils extends LazyLogging {

  def targetNamespace(clientNamespace: String, namespace: K8sNamespace): K8sNamespace =
    if (namespace == CurrentNamespace) Namespace(clientNamespace) else namespace

  def checkIfOnOpenshift(masterURL: URL): (Boolean, Int) =
    Try {
      val urlBuilder = new HttpUrl.Builder().host(masterURL.getHost)

      if (masterURL.getPort == -1) urlBuilder.port(masterURL.getDefaultPort)
      else urlBuilder.port(masterURL.getPort)

      if (masterURL.getProtocol == "https") urlBuilder.scheme("https") else urlBuilder.scheme("http")

      val url = urlBuilder.addPathSegment("apis/route.openshift.io/v1").build()

      val httpClient = HttpClientUtils.createHttpClient(new ConfigBuilder().build)
      val response = httpClient.newCall(new Request.Builder().url(url).build).execute
      response.body().close()
      httpClient.connectionPool().evictAll()
      val success = response.isSuccessful

      (success, response.code)
    }.fold(e => {
      logger.error("Failed to distinguish between Kubernetes and OpenShift", e)
      (false, -1)
    }, identity)

  def checkKubeEnv[T, F[_]: Sync](client: KubernetesClient): F[Option[Boolean]] =
    Sync[F].delay {
      val (onOpenShift, code) = checkIfOnOpenshift(client.getMasterUrl)
      if (onOpenShift) logger.debug(s"Returned code: $code. We are on OpenShift.")
      else logger.debug(s"Returned code: $code. We are not on OpenShift. Assuming, we are on Kubernetes.")
      onOpenShift.some
    }
}
