Certificates Validity Check utility
==============================

DESCRIPTION
-----------
Check SSL Certificates expiry date and expose them as a Gerrit metrics.

Configuration
=========================

The certificates-validity-checker plugin is configured in 
$site_path/etc/@PLUGIN@.config` files, example:

```text
[validation]
        endpoint = https://gerrithub.io
        endpoint = https://github.com
        checkInterval = 1
```
Configuration parameters
---------------------

```validation.checkInterval```
:  Frequency of the SSL certificates expiry date check operation
   Value should use common time unit suffixes to express their setting:
   * h, hr, hour, hours
   * d, day, days
   * w, week, weeks (`1 week` is treated as `7 days`)
   * mon, month, months (`1 month` is treated as `30 days`)
   * y, year, years (`1 year` is treated as `365 days`)
   If a time unit suffix is not specified, `hours` is assumed.
   Time intervals smaller than one hour are not supported.
   Default: 24 hours

```validation.endpoint```
:  Specify for which endpoint SSL certificates expiry date should be
   checked and expose as a Gerrit metric.
   It can be provided more than once.

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_certificates_validity_checker_certificates_number_of_day_to_expire_per_endpoint_<endpoint>_<certificate subject DNs>```