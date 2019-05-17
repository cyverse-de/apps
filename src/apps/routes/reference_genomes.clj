(ns apps.routes.reference-genomes
  (:use [common-swagger-api.schema]
        [apps.metadata.reference-genomes :only [get-reference-genome list-reference-genomes]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.reference-genome]
        [ring.util.http-response :only [ok]])
  (:require [common-swagger-api.schema.apps.reference-genomes :as schema]))

(defroutes reference-genomes
  (GET "/" []
        :query [params ReferenceGenomeListingParams]
        :return schema/ReferenceGenomesList
        :summary "List Reference Genomes."
        :description "This endpoint may be used to obtain lists of all available Reference Genomes."
        (ok (list-reference-genomes params)))

  (GET "/:reference-genome-id" []
        :path-params [reference-genome-id :- schema/ReferenceGenomeIdParam]
        :query [params SecuredQueryParams]
        :return schema/ReferenceGenome
        :summary "Get a Reference Genome."
        :description "This endpoint may be used to obtain a Reference Genome by its UUID."
        (ok (get-reference-genome reference-genome-id))))
