#!/bin/bash

. "/root/.sdkman/bin/sdkman-init.sh"

cd /root

if [ -d "/etc/secrets" ]; then
  echo "Running in k8 environment"

  PLAY_KEY=$(cat /etc/secrets/play/secret | base64)

  POSTGRES_PASSWORD=$(cat /etc/secrets/postgres/password)

  SLACK_BOT_TOKEN=""
  SLACK_CLIENT_SECRET=$(cat /etc/secrets/slack/client_secret)
  SLACK_CLIENT_ID=$(cat /etc/secrets/slack/client_id)
  SLACK_DEPLOY_URL=$(cat /etc/secrets/slack/deploy_url)

  AUTH0_DOMAIN=$(cat /etc/secrets/auth0/domain)
  AUTH0_CLIENT_ID=$(cat /etc/secrets/auth0/client_id)
  AUTH0_AUDIENCE=$(cat /etc/secrets/auth0/audience)

  SODIUM_KEY=$(cat /etc/secrets/sodium/key)

  POSTGRES_PORT=5432
  POSTGRES_HOST="meso-alert-postgres"

  EMAIL_SMTP_HOST=smtp.eu.mailgun.org
  EMAIL_SMTP_PORT=587
  EMAIL_HOST=$(cat /etc/secrets/email/username)
  EMAIL_HOST_PASSWORD=$(cat /etc/secrets/email/password)
  EMAIL_DESTINATION=feedback@symbiotica.ai

else

  echo "Running in staging environment"
  . "/root/meso-alert-config.sh"

fi

#
# Non-secret k8 configuration changes should be made directly below without
# using an environment variable.
#
cat <<EOF > application-production.conf

email.smtpHost = "${EMAIL_SMTP_HOST}"
email.smtpPort = "${EMAIL_SMTP_PORT}"
email.host = "${EMAIL_HOST}"
email.hostPassword = "${EMAIL_HOST_PASSWORD}"
email.destination = "${EMAIL_DESTINATION}"

play.filters.disabled+=play.filters.hosts.AllowedHostsFilter
play.http.secret.key="${PLAY_KEY}"
play.i18n.langs = ["en"]

slack.clientId = "${SLACK_CLIENT_ID}"
slack.clientSecret = "${SLACK_CLIENT_SECRET}"
slack.botToken = "${SLACK_BOT_TOKEN}"
slack.deployURL = "${SLACK_DEPLOY_URL}"

auth0.domain = "${AUTH0_DOMAIN}"
auth0.clientId = "${AUTH0_CLIENT_ID}"
auth0.audience = "${AUTH0_AUDIENCE}"

sodium.secret="${SODIUM_KEY}"

akka.actor.allow-java-serialization = off

akka {
  actor {

    # which serializers are available under which key
    serializers {
      proto = "akka.remote.serialization.ProtobufSerializer"
    }

    # which interfaces / traits / classes should be handled by which serializer
    serialization-bindings {
      "actors.SlackSecretsActor\$SlackSecretsEvent" = proto
      "actors.SlackSecretsActor\$SecretsState" = proto
    }
  }
}

meso-alert.db = {
  connectionPool = "HikariCP" //use HikariCP for our connection pool
  dataSourceClass = "org.postgresql.ds.PGSimpleDataSource"
  properties = {
    serverName = "${POSTGRES_HOST}"
    portNumber = "${POSTGRES_PORT}"
    databaseName = "meso-alert"
    user = "meso-alert"
    password = "${POSTGRES_PASSWORD}"
  }
  numThreads = 12
  queueSize = 50000
}

slackChat.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 12
  }
}

slackClient.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 24
  }
}

database.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 12
  }
}

email.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

encryption.dispatcher {
  executor = "thread-pool-executor"
  throughput = 1
  thread-pool-executor {
    fixed-pool-size = 2
  }
}

akka.persistence {

    # When starting many persistent actors at the same time the journal
    # and its data store is protected from being overloaded by limiting number
    # of recoveries that can be in progress at the same time. When
    # exceeding the limit the actors will wait until other recoveries have
    # been completed.
    max-concurrent-recoveries = 50

    # Fully qualified class name providing a default internal stash overflow strategy.
    # It needs to be a subclass of akka.persistence.StashOverflowStrategyConfigurator.
    # The default strategy throws StashOverflowException.
    internal-stash-overflow-strategy = "akka.persistence.ThrowExceptionConfigurator"
    journal {
        # Absolute path to the journal plugin configuration entry used by
        # persistent actor by default.
        # Persistent actor can override journalPluginId method
        # in order to rely on a different journal plugin.
        plugin = "akka.persistence.journal.inmem"
        # List of journal plugins to start automatically. Use "" for the default journal plugin.
        auto-start-journals = []
    }
    snapshot-store {
        # Absolute path to the snapshot plugin configuration entry used by
        # persistent actor by default.
        # Persistent actor can override snapshotPluginId method
        # in order to rely on a different snapshot plugin.
        # It is not mandatory to specify a snapshot store plugin.
        # If you don't use snapshots you don't have to configure it.
        # Note that Cluster Sharding is using snapshots, so if you
        # use Cluster Sharding you need to define a snapshot store plugin.
        plugin = "akka.persistence.snapshot-store.local"
        # List of snapshot stores to start automatically. Use "" for the default snapshot store.
        auto-start-snapshot-stores = []
    }
    # used as default-snapshot store if no plugin configured
    # (see akka.persistence.snapshot-store)
    no-snapshot-store {
      class = "akka.persistence.snapshot.NoSnapshotStore"
    }
    # Default reliable delivery settings.
    at-least-once-delivery {
        # Interval between re-delivery attempts.
        redeliver-interval = 5s
        # Maximum number of unconfirmed messages that will be sent in one
        # re-delivery burst.
        redelivery-burst-limit = 10000
        # After this number of delivery attempts a
        # ReliableRedelivery.UnconfirmedWarning, message will be sent to the actor.
        warn-after-number-of-unconfirmed-attempts = 5
        # Maximum number of unconfirmed messages that an actor with
        # AtLeastOnceDelivery is allowed to hold in memory.
        max-unconfirmed-messages = 100000
    }
    # Default persistent extension thread pools.
    dispatchers {
        # Dispatcher used by every plugin which does not declare explicit
        # plugin-dispatcher field.
        default-plugin-dispatcher {
            type = PinnedDispatcher
            executor = "thread-pool-executor"
        }
        # Default dispatcher for message replay.
        default-replay-dispatcher {
            type = Dispatcher
            executor = "fork-join-executor"
            fork-join-executor {
                parallelism-min = 2
                parallelism-max = 8
            }
        }
        # Default dispatcher for streaming snapshot IO
        default-stream-dispatcher {
            type = Dispatcher
            executor = "fork-join-executor"
            fork-join-executor {
                parallelism-min = 2
                parallelism-max = 8
            }
        }
    }

    # Fallback settings for journal plugin configurations.
    # These settings are used if they are not defined in plugin config section.
    journal-plugin-fallback {

      # Fully qualified class name providing journal plugin api implementation.
      # It is mandatory to specify this property.
      # The class must have a constructor without parameters or constructor with
      # one com.typesafe.config.Config parameter.
      class = ""

      # Dispatcher for the plugin actor.
      plugin-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"

      # Dispatcher for message replay.
      replay-dispatcher = "akka.persistence.dispatchers.default-replay-dispatcher"

      # Removed: used to be the Maximum size of a persistent message batch written to the journal.
      # Now this setting is without function, PersistentActor will write as many messages
      # as it has accumulated since the last write.
      max-message-batch-size = 200

      # If there is more time in between individual events gotten from the journal
      # recovery than this the recovery will fail.
      # Note that it also affects reading the snapshot before replaying events on
      # top of it, even though it is configured for the journal.
      recovery-event-timeout = 30s

      circuit-breaker {
        max-failures = 10
        call-timeout = 10s
        reset-timeout = 30s
      }

      # The replay filter can detect a corrupt event stream by inspecting
      # sequence numbers and writerUuid when replaying events.
      replay-filter {
        # What the filter should do when detecting invalid events.
        # Supported values:
        # repair-by-discard-old : discard events from old writers,
        #                           warning is logged
        # fail : fail the replay, error is logged
        # warn : log warning but emit events untouched
        # off : disable this feature completely
        mode = repair-by-discard-old

        # It uses a look ahead buffer for analyzing the events.
        # This defines the size (in number of events) of the buffer.
        window-size = 100

        # How many old writerUuid to remember
        max-old-writers = 10

        # Set this to on to enable detailed debug logging of each
        # replayed event.
        debug = off
      }
    }

    # Fallback settings for snapshot store plugin configurations
    # These settings are used if they are not defined in plugin config section.
    snapshot-store-plugin-fallback {

      # Fully qualified class name providing snapshot store plugin api
      # implementation. It is mandatory to specify this property if
      # snapshot store is enabled.
      # The class must have a constructor without parameters or constructor with
      # one com.typesafe.config.Config parameter.
      class = ""

      # Dispatcher for the plugin actor.
      plugin-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"

      circuit-breaker {
        max-failures = 5
        call-timeout = 20s
        reset-timeout = 60s
      }

      # Set this to true if successful loading of snapshot is not necessary.
      # This can be useful when it is alright to ignore snapshot in case of
      # for example deserialization errors. When snapshot loading fails it will instead
      # recover by replaying all events.
      # Don't set to true if events are deleted because that would
      # result in wrong recovered state if snapshot load fails.
      snapshot-is-optional = false

    }

  fsm {
    # PersistentFSM saves snapshots after this number of persistent
    # events. Snapshots are used to reduce recovery times.
    # When you disable this feature, specify snapshot-after = off.
    # To enable the feature, specify a number like snapshot-after = 1000
    # which means a snapshot is taken after persisting every 1000 events.
    snapshot-after = off
  }

  # DurableStateStore settings
  state {
    # Absolute path to the KeyValueStore plugin configuration entry used by
    # DurableStateBehavior actors by default.
    # DurableStateBehavior can override durableStateStorePluginId method (withDurableStateStorePluginId)
    # in order to rely on a different plugin.
    plugin = ""
  }

  # Fallback settings for DurableStateStore plugin configurations
  # These settings are used if they are not defined in plugin config section.
  state-plugin-fallback {
    recovery-timeout = 30s
  }
}

# Protobuf serialization for the persistent extension messages.
akka.actor {
    serializers {
        akka-persistence-message = "akka.persistence.serialization.MessageSerializer"
        akka-persistence-snapshot = "akka.persistence.serialization.SnapshotSerializer"
    }
    serialization-bindings {
        "akka.persistence.serialization.Message" = akka-persistence-message
        "akka.persistence.serialization.Snapshot" = akka-persistence-snapshot
    }
    serialization-identifiers {
        "akka.persistence.serialization.MessageSerializer" = 7
        "akka.persistence.serialization.SnapshotSerializer" = 8
    }
}


###################################################
# Persistence plugins included with the extension #
###################################################

# In-memory journal plugin.
akka.persistence.journal.inmem {
    # Class name of the plugin.
    class = "akka.persistence.journal.inmem.InmemJournal"
    # Dispatcher for the plugin actor.
    plugin-dispatcher = "akka.actor.default-dispatcher"

    # Turn this on to test serialization of the events
    test-serialization = off
}

# Local file system snapshot store plugin.
akka.persistence.snapshot-store.local {
    # Class name of the plugin.
    class = "akka.persistence.snapshot.local.LocalSnapshotStore"
    # Dispatcher for the plugin actor.
    plugin-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"
    # Dispatcher for streaming snapshot IO.
    stream-dispatcher = "akka.persistence.dispatchers.default-stream-dispatcher"
    # Storage location of snapshot files.
    dir = "/root/snapshots"
    # Number load attempts when recovering from the latest snapshot fails
    # yet older snapshot files are available. Each recovery attempt will try
    # to recover using an older than previously failed-on snapshot file
    # (if any are present). If all attempts fail the recovery will fail and
    # the persistent actor will be stopped.
    max-load-attempts = 3
}

# LevelDB journal plugin.
# Note: this plugin requires explicit LevelDB dependency, see below.
akka.persistence.journal.leveldb {
    # Class name of the plugin.
    class = "akka.persistence.journal.leveldb.LeveldbJournal"
    # Dispatcher for the plugin actor.
    plugin-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"
    # Dispatcher for message replay.
    replay-dispatcher = "akka.persistence.dispatchers.default-replay-dispatcher"
    # Storage location of LevelDB files.
    dir = "journal"
    # Use fsync on write.
    fsync = on
    # Verify checksum on read.
    checksum = off
    # Native LevelDB (via JNI) or LevelDB Java port.
    native = on
    # Number of deleted messages per persistence id that will trigger journal compaction
    compaction-intervals {
    }
}

# Shared LevelDB journal plugin (for testing only).
# Note: this plugin requires explicit LevelDB dependency, see below.
akka.persistence.journal.leveldb-shared {
    # Class name of the plugin.
    class = "akka.persistence.journal.leveldb.SharedLeveldbJournal"
    # Dispatcher for the plugin actor.
    plugin-dispatcher = "akka.actor.default-dispatcher"
    # Timeout for async journal operations.
    timeout = 10s
    store {
        # Dispatcher for shared store actor.
        store-dispatcher = "akka.persistence.dispatchers.default-plugin-dispatcher"
        # Dispatcher for message replay.
        replay-dispatcher = "akka.persistence.dispatchers.default-replay-dispatcher"
        # Storage location of LevelDB files.
        dir = "journal"
        # Use fsync on write.
        fsync = on
        # Verify checksum on read.
        checksum = off
        # Native LevelDB (via JNI) or LevelDB Java port.
        native = on
        # Number of deleted messages per persistence id that will trigger journal compaction
        compaction-intervals {
        }
    }
}

akka.persistence.journal.proxy {
  # Class name of the plugin.
  class = "akka.persistence.journal.PersistencePluginProxy"
  # Dispatcher for the plugin actor.
  plugin-dispatcher = "akka.actor.default-dispatcher"
  # Set this to on in the configuration of the ActorSystem
  # that will host the target journal
  start-target-journal = off
  # The journal plugin config path to use for the target journal
  target-journal-plugin = ""
  # The address of the proxy to connect to from other nodes. Optional setting.
  target-journal-address = ""
  # Initialization timeout of target lookup
  init-timeout = 10s
}

akka.persistence.snapshot-store.proxy {
  # Class name of the plugin.
  class = "akka.persistence.journal.PersistencePluginProxy"
  # Dispatcher for the plugin actor.
  plugin-dispatcher = "akka.actor.default-dispatcher"
  # Set this to on in the configuration of the ActorSystem
  # that will host the target snapshot-store
  start-target-snapshot-store = off
  # The journal plugin config path to use for the target snapshot-store
  target-snapshot-store-plugin = ""
  # The address of the proxy to connect to from other nodes. Optional setting.
  target-snapshot-store-address = ""
  # Initialization timeout of target lookup
  init-timeout = 10s
}

EOF

mkdir /root/snapshots
unzip ./target/universal/meso-alert-1.0-SNAPSHOT.zip
./meso-alert-1.0-SNAPSHOT/bin/meso-alert -Dconfig.file=application-production.conf
