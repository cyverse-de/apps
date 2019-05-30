(ns apps.routes.admin.reference-genomes
  (:use [apps.metadata.reference-genomes
         :only [add-reference-genome
                delete-reference-genome
                update-reference-genome]]
        [apps.routes.params :only [SecuredQueryParams]]
        [apps.routes.schemas.reference-genome :only [ReferenceGenomeDeletionParams]]
        [common-swagger-api.schema]
        [common-swagger-api.schema.apps.reference-genomes
         :only [ReferenceGenome
                ReferenceGenomeIdParam]]
        [ring.util.http-response :only [ok]])
  (:require [common-swagger-api.schema.apps.admin.reference-genomes :as schema]))

(defroutes reference-genomes
  (POST "/" []
        :query [params SecuredQueryParams]
        :body [body schema/ReferenceGenomeAddRequest]
        :return ReferenceGenome
        :summary schema/ReferenceGenomeAddSummary
        :description schema/ReferenceGenomeAddDocs
        (ok (add-reference-genome body)))

  (DELETE "/:reference-genome-id" []
          :path-params [reference-genome-id :- ReferenceGenomeIdParam]
          :query [params ReferenceGenomeDeletionParams]
          :summary schema/ReferenceGenomeDeleteSummary
          :description schema/ReferenceGenomeDeleteDocs
          (ok (delete-reference-genome reference-genome-id params)))

  (PATCH "/:reference-genome-id" []
         :path-params [reference-genome-id :- ReferenceGenomeIdParam]
         :query [params SecuredQueryParams]
         :body [body schema/ReferenceGenomeUpdateRequest]
         :return ReferenceGenome
         :summary schema/ReferenceGenomeUpdateSummary
         :description schema/ReferenceGenomeUpdateDocs
         (ok (update-reference-genome (assoc body :id reference-genome-id)))))
