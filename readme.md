# Overview

Provides `cron`-schedulable scripts for automating startup and shutdown of (VirtualBox) virutal machines.

# Motivation

Codify logic for 'fancy' startup and shutdown. That is, at shutdown-time, capture an online snapshot before initiating shutdown.
Then, on startup, restore from the online snapshot - allowing to begin from the previous running state.

Leverage the `cron` to schedule execution of startup and shutdown scripts.

For example, consider running an home media server via virtual machine. It may be reasonable to power off the virtual machine between midnight and 8 AM. 
Further consider a development virtual machine where the state of the desktop state is non-trivial (multiple editors, multiple browsers, etc.). It may be reasonable to poweroff the virtual machine at the close of business hours.

# Configuration

Configurations are specified as Clojure EDN. 

The shape of a configuration is as follows (via example)

```clojure
{:version 1.0
 :virtual-machines [{:name "vm1"
                     :start-type :gui}
                    {:name "vm2"
                     :start-type :separate}]}
```

The value associated with the top-level key, :virtual-machines, is a vector of maps representing virtual machine configurations. 

Each virtual machine configuration contains a name, and start-type
* name: the name of the virtual machine (case-sensitive)
* start-type (applies to `startup`, unnecessary for shutdown-only configurations)

# Execution

## Manual Execution

Using [Babashka](https://github.com/babashka/babashka), from the repository root:

```bash
bb -m scheduling.startup my-config.edn
```
or
```
bb -m scheduling.shutdown my-config.edn
```

## Cron Exection

Use the provided scripts, startup.sh and stutdown.sh. Each will need to be made executable (i.e. `chmod +x startup.sh` and `chmod +x shutdown.sh`).

Use `crontab -e` to add entries to `cron` for execution. 
For hourly execution, use the following schedule:
```
# startup.sh
0 8-17 * * * /path/to/startup.sh # run at the top of every hour between 8AM and 5PM

# shutdown.sh
0 0-7 * * * /path/to/shutdown.sh # run at the top of every hour between midnight and 7AM
```

# Scheduling

`startup` and `shutdown` act as no-ops when a virtual machine is already in the desired state. Therefore, it is safe to continuously run the scripts without concern for repeatedly creating wasteful snapshots.

Executing the scripts on an hourly schedule should prove often enough.
