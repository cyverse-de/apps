(ns apps.routes.schemas.permission
  (:use [apps.routes.params :only [SystemId SecuredQueryParams]]
        [common-swagger-api.schema :only [describe ErrorResponse NonBlankString]]
        [schema.core :only [defschema optional-key enum]])
  (:import [java.util UUID]))

(def AppPermissionEnum (enum "read" "write" "own" ""))
(def AnalysisPermissionEnum (enum "read" "own" ""))
(def ToolPermissionEnum AppPermissionEnum)

(defschema PermissionListerQueryParams
  (assoc SecuredQueryParams
    (optional-key :full-listing)
    (describe Boolean "If true, include permissions for the authenticated user as well.")))

(defschema Subject
  {:source_id (describe NonBlankString "The identifier of the subject source (for exmaple, 'ldap').")
   :id        (describe NonBlankString "The subject identifier.")})

(defschema QualifiedAppId
  {:system_id SystemId
   :app_id    (describe NonBlankString "The app ID")})

(defschema QualifiedAppIdList
  {:apps (describe [QualifiedAppId] "A List of app IDs")})

(defschema SubjectPermissionListElement
  {:subject    (describe Subject "The user or group identification.")
   :permission (describe AppPermissionEnum "The permission level assigned to the subject")})

(defschema AppPermissionListElement
  (assoc QualifiedAppId
    (optional-key :name) (describe NonBlankString "The app name")
    :permissions         (describe [SubjectPermissionListElement] "The list of subject permissions for the app")))

(defschema AppPermissionListing
  {:apps (describe [AppPermissionListElement] "The list of app permissions")})

(defschema AppSharingRequestElement
  (assoc QualifiedAppId
    :permission (describe AppPermissionEnum "The requested permission level")))

(defschema AppSharingResponseElement
  (assoc AppSharingRequestElement
    :app_name             (describe NonBlankString "The app name")
    :success              (describe Boolean "A Boolean flag indicating whether the sharing request succeeded")
    (optional-key :error) (describe ErrorResponse "Information about any error that may have occurred")))

(defschema SubjectAppSharingRequestElement
  {:subject (describe Subject "The user or group identification.")
   :apps    (describe [AppSharingRequestElement] "The list of app sharing requests for the subject")})

(defschema SubjectAppSharingResponseElement
  (assoc SubjectAppSharingRequestElement
    :apps (describe [AppSharingResponseElement] "The list of app sharing responses for the subject")))

(defschema AppSharingRequest
  {:sharing (describe [SubjectAppSharingRequestElement] "The list of app sharing requests")})

(defschema AppSharingResponse
  {:sharing (describe [SubjectAppSharingResponseElement] "The list of app sharing responses")})

(defschema AppUnsharingResponseElement
  (assoc QualifiedAppId
    :app_name             (describe NonBlankString "The app name")
    :success              (describe Boolean "A Boolean flag indicating whether the unsharing request succeeded")
    (optional-key :error) (describe ErrorResponse "Information about any error that may have occurred")))

(defschema SubjectAppUnsharingRequestElement
  {:subject (describe Subject "The user or group identification.")
   :apps    (describe [QualifiedAppId] "The list of app unsharing requests for the subject.")})

(defschema SubjectAppUnsharingResponseElement
  (assoc SubjectAppUnsharingRequestElement
    :apps (describe [AppUnsharingResponseElement] "The list of app sharing responses for the subject")))

(defschema AppUnsharingRequest
  {:unsharing (describe [SubjectAppUnsharingRequestElement] "The list of app unsharing requests")})

(defschema AppUnsharingResponse
  {:unsharing (describe [SubjectAppUnsharingResponseElement] "The list of app unsharing responses")})

(defschema AnalysisIdList
  {:analyses (describe [UUID] "A List of analysis IDs")})

(defschema AnalysisPermissionListElement
  {:id          (describe UUID "The analysis ID")
   :name        (describe NonBlankString "The analysis name")
   :permissions (describe [SubjectPermissionListElement] "The list of subject permissions for the analysis")})

(defschema AnalysisPermissionListing
  {:analyses (describe [AnalysisPermissionListElement] "The list of analysis permissions")})

(defschema AnalysisSharingRequestElement
  {:analysis_id (describe UUID "The analysis ID")
   :permission  (describe AnalysisPermissionEnum "The requested permission level")})

(defschema AnalysisSharingResponseElement
  (assoc AnalysisSharingRequestElement
    :analysis_name                (describe NonBlankString "The analysis name")
    :success                      (describe Boolean "A Boolean flag indicating whether the sharing request succeeded")
    (optional-key :outputs_error) (describe NonBlankString "A brief reason for any result folder sharing errors")
    (optional-key :app_error)     (describe NonBlankString "A brief reason for any app sharing errors")
    (optional-key :error)         (describe ErrorResponse "Information about any error that may have occurred")))

(defschema SubjectAnalysisSharingRequestElement
  {:subject  (describe Subject "The user or group identification.")
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
   (optional-key :outputs_error) (describe NonBlankString "A brief reason for the result folder unsharing error")
   (optional-key :error)         (describe ErrorResponse "Information about any error that may have occurred")})

(defschema SubjectAnalysisUnsharingRequestElement
  {:subject  (describe Subject "The user or group identification.")
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

(defschema ToolIdList
  {:tools (describe [UUID] "A List of Tool IDs")})

(defschema ToolPermissionListElement
  {:id          (describe UUID "The Tool ID")
   :name        (describe NonBlankString "The Tool name")
   :permissions (describe [SubjectPermissionListElement] "The list of subject permissions for the Tool")})

(defschema ToolPermissionListing
  {:tools (describe [ToolPermissionListElement] "The list of Tool permissions")})

(defschema ToolSharingRequestElement
  {:tool_id    (describe UUID "The Tool ID")
   :permission (describe ToolPermissionEnum "The requested permission level")})

(defschema ToolSharingResponseElement
  (assoc ToolSharingRequestElement
    :tool_name            (describe NonBlankString "The Tool name")
    :success              (describe Boolean "A Boolean flag indicating whether the sharing request succeeded")
    (optional-key :error) (describe ErrorResponse "Information about any error that may have occurred")))

(defschema SubjectToolSharingRequestElement
  {:subject (describe Subject "The user or group identification.")
   :tools   (describe [ToolSharingRequestElement] "The list of Tool sharing requests for the subject")})

(defschema SubjectToolSharingResponseElement
  (assoc SubjectToolSharingRequestElement
    :tools (describe [ToolSharingResponseElement] "The list of Tool sharing responses for the subject")))

(defschema ToolSharingRequest
  {:sharing (describe [SubjectToolSharingRequestElement] "The list of Tool sharing requests")})

(defschema ToolSharingResponse
  {:sharing (describe [SubjectToolSharingResponseElement] "The list of Tool sharing responses")})

(defschema ToolUnsharingResponseElement
  {:tool_id              (describe UUID "The Tool ID")
   :tool_name            (describe NonBlankString "The Tool name")
   :success              (describe Boolean "A Boolean flag indicating whether the unsharing request succeeded")
   (optional-key :error) (describe ErrorResponse "Information about any error that may have occurred")})

(defschema SubjectToolUnsharingRequestElement
  {:subject (describe Subject "The user or group identification.")
   :tools   (describe [UUID] "The identifiers of the Tools to unshare")})

(defschema SubjectToolUnsharingResponseElement
  (assoc SubjectToolUnsharingRequestElement
    :tools (describe [ToolUnsharingResponseElement] "The list of Tool unsharing responses for the subject")))

(defschema ToolUnsharingRequest
  {:unsharing (describe [SubjectToolUnsharingRequestElement] "The list of unsharing requests for individual subjects")})

(defschema ToolUnsharingResponse
  {:unsharing
   (describe [SubjectToolUnsharingResponseElement] "The list of unsharing responses for individual subjects")})
