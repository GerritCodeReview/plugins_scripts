// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.google.common.base.Strings
import com.google.common.flogger.FluentLogger
import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.metrics.CallbackMetric1
import com.google.gerrit.metrics.Description
import com.google.gerrit.metrics.Field
import com.google.gerrit.metrics.MetricMaker
import com.google.gerrit.server.config.ConfigUtil
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.WorkQueue
import com.google.gerrit.server.logging.Metadata
import com.google.inject.Inject
import com.google.inject.Singleton

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.ScheduledFuture

import static java.util.concurrent.TimeUnit.HOURS
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

@Singleton
@Listen
class CertificatesValidityChecker implements LifecycleListener {
  private static final int DEFAULT_CHECK_INTERVAL = 24
  private final WorkQueue queue
  private final Long checkIntervalInMillis
  private final PluginConfigFactory config
  private final String pluginName
  private final CertificatesCheckMetrics metrics

  private ScheduledFuture<?> certificatesValidityChecksTask
  private List<String> endpoints

  @Inject
  CertificatesValidityChecker(WorkQueue queue, PluginConfigFactory cfg,
                                   CertificatesCheckMetrics metrics,
                                   @PluginName String pluginName) {
    this.metrics = metrics
    this.queue = queue
    this.config = cfg
    this.pluginName = pluginName
    this.checkIntervalInMillis = getCheckInterval(cfg, pluginName)
  }

  @Override
  void start() {
    endpoints = getEndpointsList(config, pluginName)
    certificatesValidityChecksTask = queue.getDefaultQueue()
        .scheduleAtFixedRate(
            new CheckCertificatesValidityTask(metrics, endpoints),
            SECONDS.toMillis(1),
            checkIntervalInMillis,
            MILLISECONDS)
  }

  @Override
  void stop() {
    if (certificatesValidityChecksTask != null) {
      certificatesValidityChecksTask.cancel(true)
      certificatesValidityChecksTask = null
    }
  }

  private Long getCheckInterval(PluginConfigFactory cfg, String pluginName) {
    String fromConfig =
        Strings.nullToEmpty(cfg.getGlobalPluginConfig(pluginName).getString("validation",null,"checkInterval"))
    return HOURS.toMillis(ConfigUtil.getTimeUnit(fromConfig, DEFAULT_CHECK_INTERVAL, HOURS))
  }

  private List<String> getEndpointsList(PluginConfigFactory cfg, String pluginName) {
    return cfg.getGlobalPluginConfig(pluginName).getStringList("validation",null,"endpoint")
  }

  @Singleton
  private static class CertificatesCheckMetrics {
    private static final Field<String> ENDPOINT_NAME =
        Field.ofString("endpoint_name", Metadata.Builder.&cacheName).build()
    private final CallbackMetric1<String, Integer> metrics

    @Inject
    CertificatesCheckMetrics(MetricMaker metricMaker) {
      this.metrics =
          metricMaker.newCallbackMetric(
              "certificates/number_of_day_to_expire/per_endpoint",
              Integer.class,
              new Description("Per-endpoint certificate expiration date")
                  .setGauge()
                  .setUnit("days"),
              ENDPOINT_NAME)
    }

    def setMetric(String endpoint, int numberOfDays) {
      metrics.set(endpoint, numberOfDays)
    }
  }

  private static class CheckCertificatesValidityTask implements Runnable {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass()
    private final CertificatesCheckMetrics metrics
    private final List<String> endpoints
    private final HostnameVerifier hostnameVerifier

    CheckCertificatesValidityTask(CertificatesCheckMetrics metrics, List<String> endpoints) {
      this.endpoints = endpoints
      this.metrics = metrics
      this.hostnameVerifier =  { a, b -> true }
    }

    @Override
    void run() {
      for (String endpoint : endpoints) {
        logger.atInfo().log("Checking certificate expiry date for %s endpoint", endpoint)
        HttpsURLConnection conn
        try {
          conn = openConnection(endpoint)
          Certificate[] certs = conn.getServerCertificates()
          for (Certificate cert : certs) {
            if (cert instanceof X509Certificate) {
              def numberOfDaysToExpire = Duration
                  .between(new Date().toInstant(), cert.notAfter.toInstant()).toDays()
              metrics
                  .setMetric(
                      "${endpoint}_${cert.subjectDN.toString()}",
                      numberOfDaysToExpire.intValue())
            }
          }
        } catch(e) {
          logger.atSevere()
              .withCause(e)
              .log("Cannot check certificates expiry date for %s endpoint", endpoint)
        } finally {
          if (conn != null) {
            conn.disconnect()
          }
        }
      }
    }

    private HttpsURLConnection openConnection(String endpoint) {
      def url = new URL(endpoint)
      def conn = url.openConnection() as HttpsURLConnection
      conn.setHostnameVerifier(hostnameVerifier)
      def responseCode = conn.getResponseCode()
      logger
          .atInfo()
          .log("Opening connection for %s endpoint successful, response code %s",
              endpoint, responseCode)
      conn
    }
  }
}
