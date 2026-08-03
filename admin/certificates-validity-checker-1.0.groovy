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

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final int DEFAULT_CHECK_INTERVAL_HOURS = 24
  private static final int DEFAULT_CHECK_TIMEOUT_MSEC = 1000
  private final WorkQueue queue
  private final PluginConfigFactory config
  private final String pluginName
  private final CertificatesCheckMetrics metrics

  private ScheduledFuture<?> certificatesValidityChecksTask
  private List<String> endpoints
  private Long checkIntervalInMillis
  private boolean started

  @Inject
  CertificatesValidityChecker(WorkQueue queue, PluginConfigFactory cfg,
                                   CertificatesCheckMetrics metrics,
                                   @PluginName String pluginName) {
    this.metrics = metrics
    this.queue = queue
    this.config = cfg
    this.pluginName = pluginName
  }

  @Override
  void start() {
    if (started) {
      logger.atWarning().log("Plugin already started: ignoring duplicate start")
      return
    }

    endpoints = getEndpointsList(config, pluginName)
    long checkIntervalInHours = getCheckIntervalHours(config, pluginName)
    int timeout = config.getGlobalPluginConfig(pluginName)
                        .getInt("validation", null, "timeout", DEFAULT_CHECK_TIMEOUT_MSEC)
    logger.atInfo().log("Checking certificates expiry date every %d hours (timeout=%d msec) for %s",
      checkIntervalInHours, timeout, endpoints)

    certificatesValidityChecksTask = queue.getDefaultQueue()
        .scheduleAtFixedRate(
            new CheckCertificatesValidityTask(metrics, endpoints, timeout),
            SECONDS.toMillis(1),
            HOURS.toMillis(checkIntervalInHours),
            MILLISECONDS)
    started = true
  }

  @Override
  void stop() {
    if (certificatesValidityChecksTask != null) {
      certificatesValidityChecksTask.cancel(true)
      certificatesValidityChecksTask = null
    }
    started = false
  }

  private Long getCheckIntervalHours(PluginConfigFactory cfg, String pluginName) {
    String fromConfig =
        Strings.nullToEmpty(cfg.getGlobalPluginConfig(pluginName).getString("validation",null,"checkInterval"))
    return ConfigUtil.getTimeUnit(fromConfig, DEFAULT_CHECK_INTERVAL_HOURS, HOURS)
  }

  private List<String> getEndpointsList(PluginConfigFactory cfg, String pluginName) {
    return cfg.getGlobalPluginConfig(pluginName).getStringList("validation",null,"endpoint")
  }

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
    private static final int DNS_TYPE = 2 // The GeneralNameInterface.NAME_DNS value, inaccessible because of being an internal package

    private final CertificatesCheckMetrics metrics
    private final List<String> endpoints
    private final int timeout

    CheckCertificatesValidityTask(CertificatesCheckMetrics metrics, List<String> endpoints, int timeout) {
      this.endpoints = endpoints
      this.metrics = metrics
      this.timeout = timeout
    }

    @Override
    void run() {
      for (String endpoint : endpoints) {
        logger.atFine().log("Checking certificate expiry date for %s endpoint", endpoint)
        SSLSocket conn
        try {
          def (hostname, port) = parseEndpoint(endpoint)
          conn = openConnection(hostname as String, port as int)
          conn.setSoTimeout(timeout)
          conn.startHandshake()
          X509Certificate[] certs = conn.getSession().getPeerCertificates().findAll {it instanceof X509Certificate}

          for (X509Certificate cert : certs) {
            def certsAlternativeNames = cert.subjectAlternativeNames
            def certsDnsNames = certsAlternativeNames.findAll{it[0] == DNS_TYPE}.collect{it[1]}
            if (certsDnsNames.empty) {
              logger.atFine().log("Skipping X.509 Certificate %s because there are no subjectAlternativeNames of DNS type", cert)
              continue
            }

            logger.atFine().log("Checking X.509 DNS names %s against %s:%d", certsDnsNames, hostname, port)
            def certsMatchingDnsNames = certsDnsNames.findAll{isHostnameMatching(hostname as String, it as String)}
            if (certsMatchingDnsNames.empty) {
              logger.atWarning().log("Skipping X.509 Certificate %s because none of the certificate DNS names %s are matching the hostname %s", cert, certsDnsNames, hostname)
              continue
            }

            logger.atFine().log("X.509 Certificate %s has expiry date %s", cert.subjectDN, cert.notAfter)
            def numberOfDaysToExpire = Duration
                .between(new Date().toInstant(), cert.notAfter.toInstant()).toDays()
            metrics
                .setMetric(
                    hostname as String,
                    numberOfDaysToExpire.intValue())

            if (numberOfDaysToExpire >= 0) {
              logger.atInfo().log("X.509 Certificate %s is valid and has %d days left", cert.subjectDN, numberOfDaysToExpire)
            } else {
              logger.atWarning().log("X.509 Certificate for %s **EXPIRED**", cert.subjectDN)
            }
          }
        } catch(e) {
          logger.atSevere()
              .withCause(e)
              .log("Cannot check certificates expiry date for %s endpoint", endpoint)
        } finally {
            conn?.close()
        }
      }
    }

    def parseEndpoint(String endpoint) {
      def hostAndPort = endpoint.split(':')
      if (hostAndPort.size() != 2) {
        throw new IllegalArgumentException("Wrong endpoint format, expected <host>:<port> but was ${endpoint}")
      }

      hostAndPort
    }

    private boolean isHostnameMatching(String hostname, String certName) {
      // Replace the wildcard (*) with a regex wildcard (.*)
      def certPattern = certName.replaceFirst("\\*", ".*")
      hostname.matches(certPattern)
    }

    private SSLSocket openConnection(String hostname, int port) {
      (SSLSocket) SSLSocketFactory.getDefault()
          .createSocket(hostname, port);
    }
  }
}
