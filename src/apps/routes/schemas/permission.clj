(ns apps.routes.schemas.permission
  (:use [apps.routes.params :only [SecuredQueryParams]]
        [common-swagger-api.schema :only [describe ErrorResponse NonBlankString]]
        [common-swagger-api.schema.apps :only [QualifiedAppId SystemId]]
        [schema.core :only [defschema optional-key enum]])
  (:require [common-swagger-api.schema.apps.permission :as perms-schema])
  (:import [java.util UUID]))

(def AnalysisPermissionEnum (enum "read" "own" ""))

(defschema PermissionListerQueryParams
  (merge SecuredQueryParams perms-schema/PermissionListerQueryParams))

(defschema AnalysisIdList
  {:analyses (describe [UUID] "A List of analysis IDs")})

(defschema AnalysisPermissionListElement
  {:id          (describe UUID "The analysis ID")
   :name        (describe NonBlankString "The analysis name")
   :permissions (describe [perms-schema/SubjectPermissionListElement] "The list of subject permissions for the analysis")})

(defschema AnalysisPermissionListing
  {:analyses (describe [AnalysisPermissionListElement] "The list of analysis permissions")})

(defschema AnalysisSharingRequestElement
  {:analysis_id (describe UUID "The analysis ID")
   :permission  (describe AnalysisPermissionEnum "The requested permission level")})

(defschema AnalysisSharingResponseElement
  (assoc AnalysisSharingRequestElement
    :analysis_name                (describe NonBlankString "The analysis name")
    :success                      (describe Boolean "A Boolean flag indicating whether the sharing request succeeded")
    (optional-key :input_errors)  (describe [NonBlankString] "A list of any analysis input sharing errors")
    (optional-key :outputs_error) (describe NonBlankString "A brief reason for any result folder sharing errors")
    (optional-key :app_error)     (describe NonBlankString "A brief reason for any app sharing errors")
    (optional-key :error)         (describe ErrorResponse "Information about any error that may have occurred")))

(defschema SubjectAnalysisSharingRequestElement
  {:subject  (describe perms-schema/Subject "The user or group identification.")
   :analyses (describe [AnalysisSharingRequestElement] "The list of sharing requests for individual analyses")})

(defschema SubjectAnalysisSharingResponseElement
  (assoc SubjectAnalysisSharingRequestElement
    :analyses (describe [AnalysisSharingResponseElement] "The list of analysis sharing responses for the subject")))

(defschema AnalysisSharingRequest
  {:sharing (describe [SubjectAnalysisSharingRequestElement] "The list of sharing requests for individual subjects")})

(defschema AnalysisSharingResponse
  {:sharing (describe [SubjectAnalysisSharingResponseElement] "The list of sharing responses for individual subjects")})

(defschema AnalysisUnsharingResponseElement
  {:analysis_id                  (describe UUID "The analysis ID")
   :analysis_name                (describe NonBlankString "The analysis name")
   :success                      (describe Boolean "A Boolean flag indicating whether the unsharing request succeeded")
   (optional-key :input_errors)  (describe [NonBlankString] "A list of any analysis input unsharing errors")
   (optional-key :outputs_error) (describe NonBlankString "A brief reason for the result folder unsharing error")
   (optional-key :error)         (describe ErrorResponse "Information about any error that may have occurred")})

(defschema SubjectAnalysisUnsharingRequestElement
  {:subject  (describe perms-schema/Subject "The user or group identification.")
   :analyses (describe [UUID] "The identifiers of the analyses to unshare")})

(defschema SubjectAnalysisUnsharingResponseElement
  (assoc SubjectAnalysisUnsharingRequestElement
    :analyses (describe [AnalysisUnsharingResponseElement] "The list of analysis unsharing responses for the subject")))

(defschema AnalysisUnsharingRequest
  {:unsharing
   (describe [SubjectAnalysisUnsharingRequestElement] "The list of unsharing requests for individual subjects")})

(defschema AnalysisUnsharingResponse
  {:unsharing
   (describe [SubjectAnalysisUnsharingResponseElement] "The list of unsharing responses for individual subjects")})
