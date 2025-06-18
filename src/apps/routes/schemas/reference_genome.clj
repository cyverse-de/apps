(ns apps.routes.schemas.reference-genome
  (:require
   [apps.routes.params :refer [SecuredQueryParams]]
   [schema.core :refer [defschema]]
   [common-swagger-api.schema.apps.reference-genomes :as schema]
   [common-swagger-api.schema.apps.admin.reference-genomes :as admin-schema]))

(defschema ReferenceGenomeListingParams
  (merge SecuredQueryParams
         schema/ReferenceGenomeListingParams))

(defschema ReferenceGenomeDeletionParams
  (merge SecuredQueryParams
         admin-schema/ReferenceGenomeDeletionParams))
