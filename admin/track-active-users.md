Track Active Users
==============================

DESCRIPTION
-----------
Track user's activity over REST, SSH and UI and allow disabling inactive
accounts after the configured inactivity period.

Configuration
=========================

The track-active-users plugin is configured in 
$site_path/etc/gerrit.config` files, example:

```text
[plugin "@PLUGIN@"]
  autoDisableInactive = true
  autoDisableInterval = 1h 

[cache "@PLUGIN@.users_cache"]
  maxAge = 90d
```

Configuration parameters
---------------------

```plugin.@PLUGIN@.autoDisableInactive```
:  Set to `true` to enable automatic user account disablement when they
   are not active for `cache."@PLUGIN@.users_cache.maxAge` period.
   Default: `false`

```plugin.@PLUGIN@.autoDisableInterval```
:  Specify the interval for periodic check of inactive accounts.
   Value should use common time unit suffixes to express their setting:
   * h, hr, hour, hours
   * d, day, days
   * w, week, weeks (`1 week` is treated as `7 days`)
   * mon, month, months (`1 month` is treated as `30 days`)
   * y, year, years (`1 year` is treated as `365 days`)
   If a time unit suffix is not specified, `hours` is assumed.
   Default: 1 hour

```cache."@PLUGIN@.users_cache".maxAge```
:  Maximum allowed inactivity time for user.
   Value should use common time unit suffixes to express their setting:
   * h, hr, hour, hours
   * d, day, days
   * w, week, weeks (`1 week` is treated as `7 days`)
   * mon, month, months (`1 month` is treated as `30 days`)
   * y, year, years (`1 year` is treated as `365 days`)
   If a time unit suffix is not specified, `hours` is assumed.
   Default: 90 days 
