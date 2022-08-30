# Overview

Provides `cron`-schedulable scripts for automating startup and shutdown of (VirtualBox) virutal machines.

# Motivation

Codify logic for 'fancy' startup and shutdown. That is, at shutdown-time, capture an online snapshot before initiating shutdown.
Then, on startup, restore from the online snapshot - allowing to begin from the previous running state.

Then, by layering on configuration and timing logic, these scripts can be scheduled to run.

For example, consider running an home media server via virtual machine. It may be reasonable to power off the virtual machine between midnight and 8 AM. 
Further consider a development virtual machine where the state of the desktop state is non-trivial (multiple editors, multiple browsers, etc.). It may be reasonable to poweroff the virtual machine at the close of business hours.

# Configuration

Configurations are specified as Clojure EDN. 

The shape of a configuration is as follows (via example)

```clojure
[{:name "vm1"
  :start-type :gui
  :schedule {:shutdown-window {:start-time "00:00:00" :duration "PT8H"}
             :startup-window {:start-time "00:10:00" :duration "PT8H"}}}
 {:name "vm2"
  :start-type :separate
  :schedule {:shutdown-window {:start-time "00:00:00" :duration "PT8H"}}}]
```

The above shows a vector of maps representing virtual machine configurations. 

Each virtual machine configuration contains a name, start-type, and schedule
* name: the name of the virtual machine (case-sensitive)
* start-type (applies to `startup`, unnecessary for shutdown-only configurations)
* schedule: a map representing scheduling information for the virtual machine
  * contains schedules as shutdown-window or startup-window that specify a start-time a duration value
    * start-time: a String parsable as [java.time.LocalTime](https://docs.oracle.com/javase/8/docs/api/java/time/LocalDateTime.html#parse-java.lang.CharSequence-)
    * duration: a String parsable as [java.time.Duration](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html#parse-java.lang.CharSequence-)

# Execution

Using [Babashka](https://github.com/babashka/babashka), from the repository root:

```bash
bb -m scheduling.startup my-config.edn
```
or
```
bb -m scheduling.shutdown my-config.edn
```

# Scheduling

`startup` and `shutdown` act as no-ops when a virtual machine is already in the desired state. Therefore, it is safe to continuously run the scripts without concern for repeatedly creating wasteful snapshots.

Executing the scripts on an hourly schedule should prove often enough.
