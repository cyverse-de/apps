(ns apps.util.config
  (:use [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [throw+]])
  (:require [async-tasks-client.core :as async-tasks-client]
            [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clojure-commons.config :as cc]
            [clojure.tools.logging :as log]
            [common-cfg.cfg :as cfg]
            [metadata-client.core :as metadata-client]
            [permissions-client.core :as pc]))

(def docs-uri "/docs")

(def svc-info
  {:desc "Framework for hosting DiscoveryEnvironment metadata services."
   :app-name "apps"
   :group-id "org.cyverse"
   :art-id "apps"
   :service "apps"})

(def ^:private props
  "A ref for storing the configuration properties."
  (ref nil))

(def ^:private config-valid
  "A ref for storing a configuration validity flag."
  (ref true))

(def ^:private configs
  "A ref for storing the symbols used to get configuration settings."
  (ref []))

(cc/defprop-optint listen-port
  "The port that apps listens to."
  [props config-valid configs]
  "apps.app.listen-port" 60000)

(cc/defprop-optstr env-name
  "The name of the DE deployment environment."
  [props config-valid configs]
  "apps.app.environment-name" "docker-compose")

(cc/defprop-optboolean tapis-enabled
  "Enables or disables all features that require connections to Tapis."
  [props config-valid configs]
  "apps.features.tapis" true)

(cc/defprop-optboolean tapis-jobs-enabled
  "Enables or disables Tapis job submission."
  [props config-valid configs]
  "apps.features.tapis.jobs" false)

(cc/defprop-optstr db-driver-class
  "The name of the JDBC driver to use."
  [props config-valid configs]
  "apps.db.driver" "org.postgresql.Driver")

(cc/defprop-optstr db-subprotocol
  "The subprotocol to use when connecting to the database (e.g.
   postgresql)."
  [props config-valid configs]
  "apps.db.subprotocol" "postgresql")

(cc/defprop-optstr db-host
  "The host name or IP address to use when
   connecting to the database."
  [props config-valid configs]
  "apps.db.host" "dedb")

(cc/defprop-optstr db-port
  "The port number to use when connecting to the database."
  [props config-valid configs]
  "apps.db.port" "5432")

(cc/defprop-optstr db-name
  "The name of the database to connect to."
  [props config-valid configs]
  "apps.db.name" "de")

(cc/defprop-optstr db-user
  "The username to use when authenticating to the database."
  [props config-valid configs]
  "apps.db.user" "de")

(cc/defprop-optstr db-password
  "The password to use when authenticating to the database."
  [props config-valid configs]
  "apps.db.password" "notprod")

(cc/defprop-optstr jex-base-url
  "The base URL to use when connecting to the JEX."
  [props config-valid configs]
  "apps.jex.base-url" "http://jex-events:60000")

(cc/defprop-optstr app-exposer-base-url
  "The base URL to use when connecting to the app-exposer service"
  [props config-valid configs]
  "apps.vice.base-url" "http://app-exposer")

(cc/defprop-optboolean vice-k8s-enabled
  "Turns on submitting VICE analyses to K8s rather than condor"
  [props config-valid configs]
  "apps.vice.k8s-enabled" true)

(cc/defprop-optstr data-info-base-url
  "The base URL to use when connecting to the JEX."
  [props config-valid configs]
  "apps.data-info.base-url" "http://data-info:60000")

(cc/defprop-optint private-tool-time-limit-seconds
  "The time limit to use when adding new private tools."
  [props config-valid configs]
  "apps.tools.private.time-limit-seconds" (* 24 60 60)) ;; 24 hours

(cc/defprop-optint private-tool-pids-limit
  "The PIDs limit to use when adding new private tools."
  [props config-valid configs]
  "apps.tools.private.pids-limit" 1024)

(cc/defprop-optint private-tool-memory-limit
  "The memory limit, in bytes, to use when adding new private tools."
  [props config-valid configs]
  "apps.tools.private.memory-limit" (* 16 1024 1024 1024)) ;; 16GB

(cc/defprop-optint default-cpu-limit
  "The CPU limit, in cores (matching the database's data type), to be used as the default resource limit when one is not set in a tool definition."
  [props config-valid configs]
  "apps.tools.default.cpu-limit" 4)

(cc/defprop-optint default-memory-limit
  "The memory limit, in bytes, to be used as the default resource limit when one is not set in a tool definition."
  [props config-valid configs]
  "apps.tools.default.memory-limit" (* 16 1024 1024 1024)) ;; 16GB

(cc/defprop-optstr workspace-root-app-category
  "The name of the root app category in a user's workspace."
  [props config-valid configs]
  "apps.workspace.root-app-category" "Workspace")

(cc/defprop-optstr workspace-default-app-categories
  "The names of the app categories immediately under the root app category in a user's workspace."
  [props config-valid configs]
  "apps.workspace.default-app-categories" "[\"Apps under development\",\"Favorite Apps\"]")

(cc/defprop-optint workspace-dev-app-category-index
  "The index of the category within a user's workspace for apps under
   development."
  [props config-valid configs]
  "apps.workspace.dev-app-category-index" 0)

(cc/defprop-optint workspace-favorites-app-category-index
  "The index of the category within a user's workspace for favorite apps."
  [props config-valid configs]
  "apps.workspace.favorites-app-category-index" 1)

(cc/defprop-optstr workspace-metadata-beta-attr-iri
  "The attr of the Beta metadata AVU."
  [props config-valid configs]
  "apps.workspace.metadata.beta.attr.iri" "n2t.net/ark:/99152/h1459")

(cc/defprop-optstr workspace-metadata-beta-attr-label
  "The label of the Beta metadata AVU attr."
  [props config-valid configs]
  "apps.workspace.metadata.beta.attr.label" "releaseStatus")

(cc/defprop-optstr workspace-metadata-beta-value
  "The value of the Beta metadata AVU."
  [props config-valid configs]
  "apps.workspace.metadata.beta.value" "beta")

(cc/defprop-optuuid workspace-public-id
  "The UUID of the default Beta app category."
  [props config-valid configs]
  "apps.workspace.public-id" (uuidify "00000000-0000-0000-0000-000000000000"))

(cc/defprop-optvec workspace-metadata-category-attrs
  "The attrs used for an app's category metadata AVUs."
  [props config-valid configs]
  "apps.workspace.metadata.category.attrs" ["rdf:type", "http://edamontology.org/has_topic"])

(cc/defprop-optstr workspace-metadata-communities-attr
  "The attr of an App Community tag AVU."
  [props config-valid configs]
  "apps.workspace.metadata.communities.attr" "cyverse-community")

(cc/defprop-optstr workspace-metadata-certified-apps-attr
  "A metadata attr that can be applied to an app that has been reviewed and certified by DE administrators."
  [props config-valid configs]
  "apps.workspace.metadata.certified.attr" "cyverse-blessed")

(cc/defprop-optstr workspace-metadata-certified-apps-value
  "The value of the AVU to apply to an app that has been reviewed and certified by DE administrators."
  [props config-valid configs]
  "apps.workspace.metadata.certified.value" "true")

(cc/defprop-str uid-domain
  "The domain name to append to the user identifier to get the fully qualified
   user identifier."
  [props config-valid configs]
  "apps.uid.domain")

(cc/defprop-optstr irods-home
  "The path to the home directory in iRODS."
  [props config-valid configs]
  "apps.irods.home" "/iplant/home")

(cc/defprop-optstr jex-batch-group-name
  "The group name to submit to the JEX for batch jobs."
  [props config-valid configs]
  "apps.batch.group" "batch_processing")

(cc/defprop-optstr ht-path-list-info-type
  "The info type for HT Analysis Path Lists."
  [props config-valid configs]
  "apps.batch.path-list.ht.info-type" "ht-analysis-path-list")

(cc/defprop-optint ht-path-list-max-paths
  "The maximum number of paths to process per HT Analysis Path List."
  [props config-valid configs]
  "apps.batch.path-list.ht.max-paths" 1000)

(cc/defprop-optstr multi-input-path-list-info-type
  "The info type for Multi-Input Path Lists."
  [props config-valid configs]
  "apps.batch.path-list.multi-input.info-type" "multi-input-path-list")

(cc/defprop-optint multi-input-path-list-max-paths
  "The maximum number of paths to process per Multi-Input Path List."
  [props config-valid configs]
  "apps.batch.path-list.multi-input.max-paths" 1000)

(cc/defprop-optint irods-path-max-len
  "The maximum length of an iRODS path, used in max HT Path List file size calculations.
   See https://github.com/irods/irods/blob/805c01f55ea23a141cb0fa3f449f5172b3a19657/lib/core/include/rodsDef.h#L59-L61"
  [props config-valid configs]
  "apps.irods.path-max-len" 1067)

(cc/defprop-optstr tapis-base-url
  "The base URL to use when connecting to Tapis."
  [props config-valid configs tapis-enabled]
  "apps.tapis.base-url" "https://cyverse.tapis.io/v3")

(cc/defprop-str tapis-key
  "The API key to use when authenticating to Tapis."
  [props config-valid configs tapis-enabled]
  "apps.tapis.key")

(cc/defprop-str tapis-secret
  "The API secret to use when authenticating to Tapis."
  [props config-valid configs tapis-enabled]
  "apps.tapis.secret")

(cc/defprop-optstr tapis-oauth-base
  "The base URL for the Tapis OAuth 2.0 endpoints."
  [props config-valid configs tapis-enabled]
  "apps.tapis.oauth-base" "https://cyverse.tapis.io/v3/oauth2")

(cc/defprop-optint tapis-oauth-refresh-window
  "The number of minutes before a token expires to refresh it."
  [props config-valid configs tapis-enabled]
  "apps.tapis.oauth-refresh-window" 5)

(cc/defprop-str tapis-redirect-uri
  "The redirect URI used after Tapis authorization."
  [props config-valid configs tapis-enabled]
  "apps.tapis.redirect-uri")

(cc/defprop-str tapis-callback-base
  "The base URL for receiving job status update callbacks from Tapis."
  [props config-valid configs tapis-enabled]
  "apps.tapis.callback-base")

(cc/defprop-optstr tapis-storage-system
  "The storage system that Tapis should use when interacting with the DE."
  [props config-valid configs tapis-enabled]
  "apps.tapis.storage-system"
  "data.cyverse.org")

(cc/defprop-optint tapis-read-timeout
  "The maximum amount of time to wait for a response from Tapis in milliseconds."
  [props config-valid configs tapis-enabled]
  "apps.tapis.read-timeout" 10000)

(cc/defprop-optint tapis-page-length
  "The maximum number of entities to receive from a single Tapis service call."
  [props config-valid configs tapis-enabled]
  "apps.tapis.page-length" 5000)

(cc/defprop-optstr pgp-keyring-path
  "The path to the PGP keyring file."
  [props config-valid configs tapis-enabled]
  "apps.pgp.keyring-path" "/etc/iplant/crypto/de-2/secring.gpg")

(cc/defprop-optstr pgp-key-password
  "The password used to unlock the PGP key."
  [props config-valid configs tapis-enabled]
  "apps.pgp.key-password" "notprod")

(cc/defprop-optstr analyses-base
  "The base URL for the analyses service."
  [props config-valid configs]
  "apps.analyses.base-url" "http://analyses")

(cc/defprop-optstr async-tasks-base
  "The base URL to use when connecting to the async-tasks services."
  [props config-valid configs]
  "apps.async-tasks.base-url" "http://async-tasks")

(def data-info-base
  (memoize
   (fn []
     (if (System/getenv "DATA_INFO_PORT")
       (cfg/env-setting "DATA_INFO_PORT")
       (data-info-base-url)))))

(cc/defprop-optstr notification-agent-base
  "The base URL for the notification agent."
  [props config-valid configs]
  "apps.notificationagent.base-url" "http://notification-agent:60000")

(cc/defprop-optstr ipg-base
  "The base URL for the iplant-groups service."
  [props config-valid configs]
  "apps.iplant-groups.base-url" "http://iplant-groups:60000")

(cc/defprop-optstr de-grouper-user
  "The username that the DE uses to authenticate to Grouper."
  [props config-valid configs]
  "apps.iplant-groups.grouper-user" "de_grouper")

(cc/defprop-optstr grouper-user-source
  "The subject ID that Grouper uses for DE users."
  [props config-valid configs]
  "apps.iplant-groups.grouper-user-source" "ldap")

(cc/defprop-optstr metadata-base
  "The base URL for the metadata service."
  [props config-valid configs]
  "apps.metadata.base-url" "http://metadata:60000")

(cc/defprop-optstr interapps-base
  "The external URL for interactive applications."
  [props config-valid configs]
  "apps.interactive-apps.base-url" "https://cyverse.run")

(cc/defprop-optint job-status-poll-interval
  "The job status polling interval in minutes."
  [props config-valid configs]
  "apps.jobs.poll-interval" 15)

(cc/defprop-optstr permissions-base
  "The base URL for the permissions service."
  [props config-valid configs]
  "apps.permissions.base-url" "http://permissions:60000")

(cc/defprop-str ui-base-url
  "The base URL for the email service."
  [props config-valid configs]
  "apps.ui.base-url")

(cc/defprop-optstr iplant-email-base-url
  "The base URL for the email service."
  [props config-valid configs]
  "apps.email.base-url" "http://iplant-email:60000")

(cc/defprop-optstr requests-base-url
   "The base URL for the requests service."
   [props config-valid configs]
   "apps.requests.base-url" "http://requests:8080")

(cc/defprop-str app-deletion-notification-src-addr
  "The source email address of app deletion notification messages."
  [props config-valid configs]
  "apps.email.app-deletion.from")

(cc/defprop-optstr app-deletion-notification-subject
  "The email subject of app deletion notification messages."
  [props config-valid configs]
  "apps.email.app-deletion.subject" "Your \"%s\" app has been administratively deprecated.")

(cc/defprop-str app-publication-request-email-src-addr
  "The source email address of the app publication request notification messages."
  [props config-valid configs]
  "apps.email.app-request.from")

(cc/defprop-str app-publication-request-email-dest-addr
  "The destination email address of the app publication request notification messages."
  [props config-valid configs]
  "apps.email.app-request.to")

(cc/defprop-optstr app-publication-request-email-subject
  "The subject to use for app publication request messages."
  [props config-valid configs]
  "apps.email.app-request.subject" "New Publication Request")

(cc/defprop-optvec trusted-registries
  "The list of registries that we trust for public tools."
  [props config-valid configs]
  "apps.tools.trusted-registries" [])

(def get-default-app-categories
  (memoize
   (fn []
     (cheshire/decode (workspace-default-app-categories) true))))

(defn- oauth-settings
  [api-name api-key api-secret auth-uri token-uri redirect-uri refresh-window]
  {:api-name       api-name
   :client-key     api-key
   :client-secret  api-secret
   :auth-uri       auth-uri
   :token-uri      token-uri
   :redirect-uri   redirect-uri
   :refresh-window (* refresh-window 60 1000)})

(def tapis-oauth-settings
  (memoize
   #(oauth-settings
     "tapis"
     (tapis-key)
     (tapis-secret)
     (str (curl/url (tapis-oauth-base) "authorize"))
     (str (curl/url (tapis-oauth-base) "tokens"))
     (tapis-redirect-uri)
     (tapis-oauth-refresh-window))))

(def permissions-client
  (memoize #(pc/new-permissions-client (permissions-base))))

(def metadata-client
  (memoize #(metadata-client/new-metadata-client (metadata-base))))

(def async-tasks-client
  (memoize #(async-tasks-client/new-async-tasks-client (async-tasks-base))))

(defn app-resource-type
  "The app resource type name. This value is hard-coded for now, but placed in this namespace so that we can easily
   convert it to a configuration setting if necessary."
  []
  "app")

(defn analysis-resource-type
  "The analysis resource type name. This value is hard-coded for now, but placed in this namespace so that we can
   easily convert it to a configuration setting if necessary."
  []
  "analysis")

(defn tool-resource-type
  "The tool resource type name. This value is hard-coded for now, but placed in this namespace so that we can easily
   convert it to a configuration setting if necessary."
  []
  "tool")

(defn log-environment
  []
  (log/warn "ENV?: apps.data-info.base-url = " (data-info-base)))

(defn- validate-config
  "Validates the configuration settings after they've been loaded."
  []
  (when-not (cc/validate-config configs config-valid)
    (throw+ {:type :clojure-commons.exception/invalid-configuration})))

(defn load-config-from-file
  "Loads the configuration settings from a file."
  [cfg-path & [{:keys [log-config?] :or {log-config? true}}]]
  (cc/load-config-from-file cfg-path props)
  (when log-config?
    (cc/log-config props)
    (log-environment))
  (validate-config))
