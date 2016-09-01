(ns apps.util.config
  (:use [kameleon.uuids :only [uuidify]]
        [slingshot.slingshot :only [throw+]])
  (:require [cemerick.url :as curl]
            [cheshire.core :as cheshire]
            [clojure-commons.config :as cc]
            [clojure.tools.logging :as log]
            [common-cfg.cfg :as cfg]
            [permissions-client.core :as pc]))

(def docs-uri "/docs")

(def svc-info
  {:desc "Framework for hosting DiscoveryEnvironment metadata services."
   :app-name "apps"
   :group-id "org.iplantc"
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

(cc/defprop-optboolean agave-enabled
  "Enables or disables all features that require connections to Agave."
  [props config-valid configs]
  "apps.features.agave" true)

(cc/defprop-optboolean agave-jobs-enabled
  "Enables or disables Agave job submission."
  [props config-valid configs]
  "apps.features.agave.jobs" false)

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

(cc/defprop-optstr data-info-base-url
  "The base URL to use when connecting to the JEX."
  [props config-valid configs]
  "apps.data-info.base-url" "http://data-info:60000")

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

(cc/defprop-optstr path-list-info-type
  "The info type for HT Analysis Path Lists."
  [props config-valid configs]
  "apps.batch.path-list.info-type" "ht-analysis-path-list")

(cc/defprop-optint path-list-max-paths
  "The maximum number of paths to process per HT Analysis Path Lists."
  [props config-valid configs]
  "apps.batch.path-list.max-paths" 16)

(cc/defprop-optint path-list-max-size
  "The maximum size of each HT Analysis Path List that can be fetched from the data-info service."
  [props config-valid configs]
  "apps.batch.path-list.max-size" 1048576)

(cc/defprop-optstr agave-base-url
  "The base URL to use when connecting to Agave."
  [props config-valid configs agave-enabled]
  "apps.agave.base-url" "https://agave.iplantc.org")

(cc/defprop-str agave-key
  "The API key to use when authenticating to Agave."
  [props config-valid configs agave-enabled]
  "apps.agave.key")

(cc/defprop-str agave-secret
  "The API secret to use when authenticating to Agave."
  [props config-valid configs agave-enabled]
  "apps.agave.secret")

(cc/defprop-optstr agave-oauth-base
  "The base URL for the Agave OAuth 2.0 endpoints."
  [props config-valid configs agave-enabled]
  "apps.agave.oauth-base" "https://agave.iplantc.org/oauth2")

(cc/defprop-optint agave-oauth-refresh-window
  "The number of minutes before a token expires to refresh it."
  [props config-valid configs agave-enabled]
  "apps.agave.oauth-refresh-window" 5)

(cc/defprop-str agave-redirect-uri
  "The redirect URI used after Agave authorization."
  [props config-valid configs agave-enabled]
  "apps.agave.redirect-uri")

(cc/defprop-str agave-callback-base
  "The base URL for receiving job status update callbacks from Agave."
  [props config-valid configs agave-enabled]
  "apps.agave.callback-base")

(cc/defprop-optstr agave-storage-system
  "The storage system that Agave should use when interacting with the DE."
  [props config-valid configs agave-enabled]
  "apps.agave.storage-system"
  "data.iplantcollaborative.org")

(cc/defprop-optint agave-read-timeout
  "The maximum amount of time to wait for a response from Agave in milliseconds."
  [props config-valid configs agave-enabled]
  "apps.agave.read-timeout" 10000)

(cc/defprop-optint agave-page-length
  "The maximum number of entities to receive from a single Agave service call."
  [props config-valid configs agave-enabled]
  "apps.agave.page-length" 5000)

(cc/defprop-optstr pgp-keyring-path
  "The path to the PGP keyring file."
  [props config-valid configs agave-enabled]
  "apps.pgp.keyring-path" "/etc/iplant/crypto/de-2/secring.gpg")

(cc/defprop-optstr pgp-key-password
  "The password used to unlock the PGP key."
  [props config-valid configs agave-enabled]
  "apps.pgp.key-password" "notprod")

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

(cc/defprop-optstr metadata-base
  "The base URL for the metadata service."
  [props config-valid configs]
  "apps.metadata.base-url" "http://metadata:60000")

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

(cc/defprop-str app-deletion-notification-src-addr
  "The source email address of app deletion notification messages."
  [props config-valid configs]
  "apps.email.app-deletion.from")

(cc/defprop-optstr app-deletion-notification-subject
  "The email subject of app deletion notification messages."
  [props config-valid configs]
  "apps.email.app-deletion.subject" "Your \"%s\" app has been administratively deprecated.")

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

(def agave-oauth-settings
  (memoize
   #(oauth-settings
     "agave"
     (agave-key)
     (agave-secret)
     (str (curl/url (agave-oauth-base) "authorize"))
     (str (curl/url (agave-oauth-base) "token"))
     (agave-redirect-uri)
     (agave-oauth-refresh-window))))

(def permissions-client
  (memoize #(pc/new-permissions-client (permissions-base))))

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
