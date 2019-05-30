(ns apps.routes.schemas.reference-genome
  (:use [common-swagger-api.schema :only [->optional-param describe]]
        [apps.routes.params]
        [schema.core :only [defschema optional-key]])
  (:require [common-swagger-api.schema.apps.reference-genomes :as schema]
            [common-swagger-api.schema.apps.admin.reference-genomes :as admin-schema]))

(defschema ReferenceGenomeListingParams
  (merge SecuredQueryParams
         schema/ReferenceGenomeListingParams))

(defschema ReferenceGenomeDeletionParams
  (merge SecuredQueryParams
         admin-schema/ReferenceGenomeDeletionParams))
