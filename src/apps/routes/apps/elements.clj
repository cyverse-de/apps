(ns apps.routes.apps.elements
  (:require
   [apps.metadata.element-listings :refer [list-elements]]
   [apps.routes.params :refer [AppElementToolListingParams AppParameterTypeParams SecuredQueryParams]]
   [apps.util.service :as service]
   [common-swagger-api.schema :refer [defroutes GET undocumented]]
   [common-swagger-api.schema.apps.elements :as elements-schema]
   [common-swagger-api.schema.tools :refer [ToolListing]]
   [compojure.route :as route]
   [ring.util.http-response :refer [ok]]))

(defroutes app-elements
  (GET "/" []
    :query [params AppElementToolListingParams]
    :summary elements-schema/AppElementsListingSummary
    :description elements-schema/AppElementsListingDocs
    (ok (list-elements "all" params)))

  (GET "/data-sources" []
    :query [params SecuredQueryParams]
    :return elements-schema/DataSourceListing
    :summary elements-schema/AppElementsDataSourceListingSummary
    :description elements-schema/AppElementsDataSourceListingDocs
    (ok (list-elements "data-sources" params)))

  (GET "/file-formats" []
    :query [params SecuredQueryParams]
    :return elements-schema/FileFormatListing
    :summary elements-schema/AppElementsFileFormatListingSummary
    :description elements-schema/AppElementsFileFormatListingDocs
    (ok (list-elements "file-formats" params)))

  (GET "/info-types" []
    :query [params SecuredQueryParams]
    :return elements-schema/InfoTypeListing
    :summary elements-schema/AppElementsInfoTypeListingSummary
    :description elements-schema/AppElementsInfoTypeListingDocs
    (ok (list-elements "info-types" params)))

  (GET "/parameter-types" []
    :query [params AppParameterTypeParams]
    :return elements-schema/ParameterTypeListing
    :summary elements-schema/AppElementsParameterTypeListingSummary
    :description elements-schema/AppElementsParameterTypeListingDocs
    (ok (list-elements "parameter-types" params)))

  (GET "/rule-types" []
    :query [params SecuredQueryParams]
    :return elements-schema/RuleTypeListing
    :summary elements-schema/AppElementsRuleTypeListingSummary
    :description elements-schema/AppElementsRuleTypeListingDocs
    (ok (list-elements "rule-types" params)))

  ;; Deprecated?
  (GET "/tools" []
    :query [params AppElementToolListingParams]
    :return ToolListing
    :summary "List App Tools"
    :description "This endpoint is used by the Discovery Environment to obtain a list of registered
        tools (usually, command-line tools) that can be executed from within the DE."
    (ok (list-elements "tools" params)))

  (GET "/tool-types" []
    :query [params SecuredQueryParams]
    :return elements-schema/ToolTypeListing
    :summary elements-schema/AppElementsToolTypeListingSummary
    :description elements-schema/AppElementsToolTypeListingDocs
    (ok (list-elements "tool-types" params)))

  (GET "/value-types" []
    :query [params SecuredQueryParams]
    :return elements-schema/ValueTypeListing
    :summary elements-schema/AppElementsValueTypeListingSummary
    :description elements-schema/AppElementsValueTypeListingDocs
    (ok (list-elements "value-types" params)))

  (undocumented (route/not-found (service/unrecognized-path-response))))
