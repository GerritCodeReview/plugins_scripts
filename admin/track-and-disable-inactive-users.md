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
[cache "@PLUGIN@.users_cache"]
  maxAge = 90d
```

Configuration parameters
---------------------

=======
```plugin.@PLUGIN@.ignoreAccountId```
:  Specify an account Id that should not be auto disabled.
   May be specified more than once to specify multiple account Ids, for example:

   ```
   ignoreAccountId = 1000001
   ignoreAccountId = 1000002
   ```

```plugin.@PLUGIN@.ignoreGroup```
:  Specify one group that includes directly or indirectly all the accounts that
   should not be auto disabled.
   May be specified more than once to specify multiple groups, for example:

   ```
   ignoreGroup = Active Developers
   ignoreGroup = Administrators
   ```

   > **NOTE**: The `Service Users` group is always added to the list of groups of
   > accounts to not disable.

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

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_track_and_disable_inactive_users_active_users```
:  Indicates the number of active users.
   A user is considered active when its inactivity period is not greater than `cache."@PLUGIN@.users_cache".maxAge` .