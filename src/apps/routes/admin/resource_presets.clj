(ns apps.routes.admin.resource-presets
  (:require [apps.persistence.resource-presets :as presets]
            [apps.routes.params :refer [SecuredQueryParams]]
            [common-swagger-api.schema
             :refer [context
                     defroutes
                     describe
                     DELETE
                     GET
                     PATCH
                     POST
                     PUT]]
            [common-swagger-api.schema.apps
             :refer [ResourcePreset
                     ResourcePresetList
                     ResourcePresetRequest
                     ResourcePresetUpdateRequest]]
            [ring.util.http-response :refer [ok]])
  (:import [java.util UUID]))

(declare body params preset-id admin-resource-presets resource-presets)

(def ResourcePresetIdParam (describe UUID "The Resource Preset's UUID"))

(defroutes admin-resource-presets
  (GET "/" []
    :query [params SecuredQueryParams]
    :return ResourcePresetList
    :summary "List Resource Presets"
    :description "Lists all resource presets, including disabled ones."
    (ok {:resource_presets (presets/get-all-resource-presets)}))

  (POST "/" []
    :query [params SecuredQueryParams]
    :body [body ResourcePresetRequest]
    :return ResourcePreset
    :summary "Create a Resource Preset"
    :description "Creates a new resource preset."
    (ok (presets/create-resource-preset body)))

  (context "/:preset-id" []
    :path-params [preset-id :- ResourcePresetIdParam]

    (GET "/" []
      :query [params SecuredQueryParams]
      :return ResourcePreset
      :summary "Get a Resource Preset"
      :description "Gets a resource preset by its identifier."
      (ok (presets/get-valid-resource-preset preset-id)))

    (PATCH "/" []
      :query [params SecuredQueryParams]
      :body [body ResourcePresetUpdateRequest]
      :return ResourcePreset
      :summary "Update a Resource Preset"
      :description "Updates an existing resource preset."
      (ok (presets/update-resource-preset preset-id body)))

    (DELETE "/" []
      :query [params SecuredQueryParams]
      :summary "Delete a Resource Preset"
      :description "Permanently deletes a resource preset."
      (ok (presets/delete-resource-preset preset-id)))

    (PUT "/default" []
      :query [params SecuredQueryParams]
      :return ResourcePreset
      :summary "Set Default Resource Preset"
      :description "Sets this preset as the global default, clearing any previous default."
      (ok (presets/set-default-resource-preset preset-id)))))

;; Non-admin route for the public listing of enabled presets.
;; Placed here alongside the admin routes for simplicity (single defroutes file
;; for a small feature); mounted at /resource-presets (not /admin/) in routes.clj.
(defroutes resource-presets
  (GET "/" []
    :query [params SecuredQueryParams]
    :return ResourcePresetList
    :summary "List Resource Presets"
    :description "Lists enabled resource presets available for analysis launch."
    (ok {:resource_presets (presets/get-resource-presets)})))
