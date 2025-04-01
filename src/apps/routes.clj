(ns apps.routes
  (:require [apps.routes.admin :as admin-routes]
            [apps.routes.admin.apps :as admin-apps-routes]
            [apps.routes.admin.reference-genomes :as admin-reference-genomes-routes]
            [apps.routes.admin.tool-requests :as admin-tool-request-routes]
            [apps.routes.analyses :as analysis-routes]
            [apps.routes.apps :as app-routes]
            [apps.routes.apps.categories :as app-category-routes]
            [apps.routes.apps.communities :as app-community-routes]
            [apps.routes.apps.elements :as app-element-routes]
            [apps.routes.apps.pipelines :as pipeline-routes]
            [apps.routes.apps.metadata :as metadata-routes]
            [apps.routes.apps.versions :as versions-routes]
            [apps.routes.bootstrap :as bootstrap-routes]
            [apps.routes.callbacks :as callback-routes]
            [apps.routes.groups :as group-routes]
            [apps.routes.integration-data :as integration-data-routes]
            [apps.routes.oauth :as oauth-routes]
            [apps.routes.reference-genomes :as reference-genome-routes]
            [apps.routes.status :as status-routes]
            [apps.routes.submissions :as submission-routes]
            [apps.routes.tools :as tool-routes]
            [apps.routes.users :as user-routes]
            [apps.routes.workspaces :as workspace-routes]
            [apps.routes.webhooks :as webhooks-routes]
            [apps.user :refer [store-current-user]]
            [apps.util.config :as config]
            [apps.util.service :as service]
            [clojure-commons.exception :as cx]
            [clojure-commons.query-params :refer [wrap-query-params]]
            [common-swagger-api.schema :as schema]
            [compojure.core :refer [wrap-routes]]
            [compojure.route :as route]
            [ring.middleware :refer [wrap-keyword-params]]
            [service-logging.middleware :refer [add-user-to-context wrap-logging clean-context]]))

(declare app)

(schema/defapi app
  {:exceptions cx/exception-handlers}
  (schema/swagger-routes
   {:ui config/docs-uri
    :options {:ui {:validatorUrl nil}}
    :data {:info {:title "Discovery Environment Apps API"
                  :description "Documentation for the Discovery Environment Apps REST API"
                  :version "2.8.1"}
           :tags [{:name "service-info", :description "Service Status Information"}
                  {:name "callbacks", :description "General callback endpoints"}
                  {:name "app-categories", :description "App Category endpoints."}
                  {:name "app-communities", :description "App Community endpoints."}
                  {:name "app-hierarchies", :description "App Hierarchy endpoints."}
                  {:name "app-element-types", :description "App Element endpoints."}
                  {:name "apps", :description "App endpoints."}
                  {:name "app-versions", :description "App Version endpoints."}
                  {:name "app-community-tags", :description "App Community tag endpoints."}
                  {:name "app-metadata", :description "App Metadata endpoints."}
                  {:name "pipelines", :description "Pipeline endpoints."}
                  {:name "analyses", :description "Analysis endpoints."}
                  {:name "bootstrap", :description "Bootstrap endpoints."}
                  {:name "tools", :description "Tool endpoints."}
                  {:name "workspaces", :description "Workspace endpoints."}
                  {:name "webhooks", :description "Webhooks endpoints."}
                  {:name "users", :description "User endpoints."}
                  {:name "tool-requests", :description "Tool Request endpoints."}
                  {:name "reference-genomes", :description "Reference Genome endpoints."}
                  {:name "oauth", :description "OAuth callback and information endpoints."}
                  {:name "submissions", :description "Analysis submission endpoints"}
                  {:name "admin-analyses", :description "Admin Analysis Endpoints"}
                  {:name "admin-apps", :description "Admin App endpoints."}
                  {:name "admin-app-community-tags", :description "Admin App Community tag endpoints."}
                  {:name "admin-app-metadata", :description "Admin App Metadata endpoints."}
                  {:name "admin-categories", :description "Admin App Category endpoints."}
                  {:name "admin-communities", :description "Admin App Community endpoints."}
                  {:name "admin-ontologies", :description "Admin App Ontology endpoints."}
                  {:name "admin-container-images", :description "Admin Tool Docker Images endpoints."}
                  {:name "admin-data-containers", :description "Admin Docker Data Container endpoints."}
                  {:name "admin-tools", :description "Admin Tool endpoints."}
                  {:name "admin-reference-genomes", :description "Admin Reference Genome endpoints."}
                  {:name "admin-tool-requests", :description "Admin Tool Request endpoints."}
                  {:name "admin-oauth", :description "Admin OAuth endpoints."}
                  {:name "admin-integration-data", :description "Admin Integration Data endpoints."}
                  {:name "admin-groups", :description "Admin Group endpoints."}
                  {:name "admin-workspaces", :description "Admin Workspace endpoints"}]}})
  (schema/context "/" []
    :middleware [clean-context
                 wrap-keyword-params
                 wrap-query-params
                 [wrap-routes wrap-logging]]
    :tags ["service-info"]
    status-routes/status

    (schema/context "/callbacks" []
      :tags ["callbacks"]
      callback-routes/callbacks))
  (schema/context "/" []
    :middleware [clean-context
                 wrap-keyword-params
                 wrap-query-params
                 add-user-to-context
                 store-current-user
                 wrap-logging]
    (schema/context "/apps/categories" []
      :tags ["app-categories"]
      app-category-routes/app-categories)
    (schema/context "/apps/communities" []
      :tags ["app-communities"]
      app-category-routes/app-communities)
    (schema/context "/apps/hierarchies" []
      :tags ["app-hierarchies"]
      app-category-routes/app-hierarchies)
    (schema/context "/apps/elements" []
      :tags ["app-element-types"]
      app-element-routes/app-elements)
    (schema/context "/apps/pipelines" []
      :tags ["pipelines"]
      pipeline-routes/pipelines)
    (schema/context "/apps/:app-id/communities" []
      :tags ["app-community-tags"]
      app-community-routes/app-community-tags)
    (schema/context "/apps/:app-id/metadata" []
      :tags ["app-metadata"]
      metadata-routes/app-metadata)
    (schema/context "/apps" []
      :tags ["apps"]
      app-routes/apps)
    (schema/context "/apps" []
      :tags ["app-versions"]
      versions-routes/app-versions)
    (schema/context "/analyses" []
      :tags ["analyses"]
      analysis-routes/analyses)
    (schema/context "/bootstrap" []
      :tags ["bootstrap"]
      bootstrap-routes/bootstrap)
    (schema/context "/tools" []
      :tags ["tools"]
      tool-routes/tools)
    (schema/context "/workspaces" []
      :tags ["workspaces"]
      workspace-routes/workspaces)
    (schema/context "/webhooks" []
      :tags ["webhooks"]
      webhooks-routes/webhooks)
    (schema/context "/users" []
      :tags ["users"]
      user-routes/users)
    (schema/context "/tool-requests" []
      :tags ["tool-requests"]
      tool-routes/tool-requests)
    (schema/context "/reference-genomes" []
      :tags ["reference-genomes"]
      reference-genome-routes/reference-genomes)
    (schema/context "/oauth" []
      :tags ["oauth"]
      oauth-routes/oauth)
    (schema/context "/submissions" []
      :tags ["submissions"]
      submission-routes/submissions)
    (schema/context "/admin/analyses" []
      :tags ["admin-analyses"]
      admin-routes/admin-analyses)
    (schema/context "/admin/apps/categories" []
      :tags ["admin-categories"]
      admin-routes/admin-categories)
    (schema/context "/admin/apps/communities" []
      :tags ["admin-communities"]
      admin-routes/admin-communities)
    (schema/context "/admin/apps/:app-id/communities" []
      :tags ["admin-app-community-tags"]
      app-community-routes/admin-app-community-tags)
    (schema/context "/admin/apps/:app-id/metadata" []
      :tags ["admin-app-metadata"]
      metadata-routes/admin-app-metadata)
    (schema/context "/admin/apps" []
      :tags ["admin-apps"]
      admin-apps-routes/admin-apps)
    (schema/context "/admin/ontologies" []
      :tags ["admin-ontologies"]
      admin-routes/admin-ontologies)
    (schema/context "/admin/reference-genomes" []
      :tags ["admin-reference-genomes"]
      admin-reference-genomes-routes/reference-genomes)
    (schema/context "/admin/tools/container-images" []
      :tags ["admin-container-images"]
      tool-routes/container-images)
    (schema/context "/admin/tools/data-containers" []
      :tags ["admin-data-containers"]
      tool-routes/admin-data-containers)
    (schema/context "/admin/tools" []
      :tags ["admin-tools"]
      tool-routes/admin-tools)
    (schema/context "/admin/tool-requests" []
      :tags ["admin-tool-requests"]
      admin-tool-request-routes/admin-tool-requests)
    (schema/context "/admin/oauth" []
      :tags ["admin-oauth"]
      oauth-routes/admin-oauth)
    (schema/context "/admin/integration-data" []
      :tags ["admin-integration-data"]
      integration-data-routes/admin-integration-data)
    (schema/context "/admin/groups" []
      :tags ["admin-groups"]
      group-routes/admin-group-routes)
    (schema/context "/admin/workspaces" []
      :tags ["admin-workspaces"]
      admin-routes/admin-workspaces)
    (schema/undocumented (route/not-found (service/unrecognized-path-response)))))
