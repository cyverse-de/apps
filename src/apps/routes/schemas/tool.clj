(ns apps.routes.schemas.tool
  (:use [apps.routes.params :only [SecuredPagingParams SecuredQueryParams]]
        [clojure-commons.error-codes]
        [common-swagger-api.schema :only [->optional-param describe ErrorResponse]]
        [schema.core :only [defschema enum optional-key]])
  (:require [common-swagger-api.schema.tools :as schema])
  (:import [java.util UUID]))

(def StatusCodeId (describe UUID "The Status Code's UUID"))

(defschema ToolIdsList
  {:tool_ids (describe [UUID] "A List of Tool IDs")})

(defschema PrivateToolDeleteParams
  (merge SecuredQueryParams schema/PrivateToolDeleteParams))

(defschema ToolUpdateParams
  (merge SecuredQueryParams
    {(optional-key :overwrite-public)
     (describe Boolean "Flag to force container settings updates of public tools.")}))

(defschema ToolsImportRequest
  {:tools (describe [schema/ToolImportRequest] "zero or more Tool definitions")})

(defschema ToolUpdateRequest
  (-> schema/ToolImportRequest
      (->optional-param :name)
      (->optional-param :version)
      (->optional-param :type)
      (->optional-param :implementation)
      (->optional-param :container)))

(defschema ToolRequestStatus
  {(optional-key :status)
   (describe String
     "The status code of the Tool Request update. The status code is case-sensitive, and if it isn't
      defined in the database already then it will be added to the list of known status codes")

   :status_date
   (describe Long "The timestamp of the Tool Request status update")

   :updated_by
   (describe String "The username of the user that updated the Tool Request status")

   (optional-key :comments)
   (describe String "The administrator comments of the Tool Request status update")})

(defschema ToolRequestStatusUpdate
  (dissoc ToolRequestStatus :updated_by :status_date))

(defschema ToolRequestDetails
  {:id
   schema/ToolRequestIdParam

   :submitted_by
   schema/SubmittedByParam

   (optional-key :phone)
   (describe String "The phone number of the user submitting the request")

   (optional-key :tool_id)
   schema/ToolRequestToolIdParam

   :name
   schema/ToolNameParam

   :description
   schema/ToolDescriptionParam

   (optional-key :source_url)
   (describe String "A link that can be used to obtain the tool")

   (optional-key :source_upload_file)
   (describe String "The path to a file that has been uploaded into iRODS")

   :documentation_url
   (describe String "A link to the tool documentation")

   :version
   schema/VersionParam

   (optional-key :attribution)
   schema/AttributionParam

   (optional-key :multithreaded)
   (describe Boolean
     "A flag indicating whether or not the tool is multithreaded. This can be `true` to indicate
      that the user requesting the tool knows that it is multithreaded, `false` to indicate that the
      user knows that the tool is not multithreaded, or omitted if the user does not know whether or
      not the tool is multithreaded")

   :test_data_path
   (describe String "The path to a test data file that has been uploaded to iRODS")

   :cmd_line
   (describe String "Instructions for using the tool")

   (optional-key :additional_info)
   (describe String
     "Any additional information that may be helpful during tool installation or validation")

   (optional-key :additional_data_file)
   (describe String
     "Any additional data file that may be helpful during tool installation or validation")

   (optional-key :architecture)
   (describe (enum "32-bit Generic" "64-bit Generic" "Others" "Don't know")
     "One of the architecture names known to the DE. Currently, the valid values are
      `32-bit Generic` for a 32-bit executable that will run in the DE,
      `64-bit Generic` for a 64-bit executable that will run in the DE,
      `Others` for tools run in a virtual machine or interpreter, and
      `Don't know` if the user requesting the tool doesn't know what the architecture is")

   :history
   (describe [ToolRequestStatus] "A history of status updates for this Tool Request")

   (optional-key :interactive)
   schema/Interactive})

(defschema ToolRequest
  (dissoc ToolRequestDetails :id :submitted_by :history))

(defschema ToolRequestListing
  {:tool_requests (describe [schema/ToolRequestSummary]
                    "A listing of high level details about tool requests that have been submitted")})

(defschema ToolRequestListingParams
  (merge SecuredPagingParams
    {(optional-key :status)
     (describe String
       "The name of a status code to include in the results. The name of the status code is case
        sensitive. If the status code isn't already defined, it will be added to the database")}))

(defschema StatusCodeListingParams
  (merge SecuredQueryParams
    {(optional-key :filter)
     (describe String
       "If this parameter is set then only the status codes that contain the string passed in this
        query parameter will be listed. This is a case-insensitive search")}))

(defschema StatusCode
  {:id          StatusCodeId
   :name        (describe String "The Status Code")
   :description (describe String "A brief description of the Status Code")})

(defschema StatusCodeListing
  {:status_codes (describe [StatusCode] "A listing of known Status Codes")})

(defschema ImagePublicAppToolListing
  {:tools (describe [schema/Tool] "Listing of Public App Tools")})
