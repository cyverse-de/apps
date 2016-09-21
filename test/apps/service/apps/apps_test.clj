(ns apps.service.apps.apps-test
  (:use [apps.service.apps.test-utils :only [get-user delete-app permanently-delete-app
                                             de-system-id fake-system-id hpc-system-id]]
        [clojure.test])
  (:require [apps.persistence.jobs :as jp]
            [apps.service.apps :as apps]
            [apps.service.apps.test-fixtures :as atf]
            [apps.test-fixtures :as tf])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)

(def ^:private fake-app-id "fakeid")

(defn- test-unrecognized-system-id [f]
  (is (thrown-with-msg? ExceptionInfo #"unrecognized system ID" (f))))

(defn- test-hpc-app-modification [f]
  (is (thrown-with-msg? ExceptionInfo #"Cannot add or modify HPC apps" (f))))

(defn- test-non-uuid [f]
  (is (thrown-with-msg? ExceptionInfo #"is not a UUID" (f))))

(defn- test-hpc-integration-data [f]
  (is (thrown-with-msg? ExceptionInfo #"Cannot list or modify integration data for HPC apps" (f))))

(defn- test-hpc-app-favorite [f]
  (is (thrown-with-msg? ExceptionInfo #"Cannot mark an HPC app as a favorite with this service" (f))))

(deftest test-app-addition-with-invalid-system-id
  (test-unrecognized-system-id #(apps/add-app (get-user :testde1) fake-system-id atf/app-definition)))

(deftest test-app-addition-with-hpc-system-id
  (test-hpc-app-modification #(apps/add-app (get-user :testde1) hpc-system-id atf/app-definition)))

(deftest test-command-preview-with-invalid-system-id
  (test-unrecognized-system-id #(apps/preview-command-line (get-user :testde1) fake-system-id atf/app-definition)))

(deftest test-command-preview-with-hpc-system-id
  (test-hpc-app-modification #(apps/preview-command-line (get-user :testde1) hpc-system-id atf/app-definition)))

(deftest test-app-shredder-with-invalid-system-id
  (test-unrecognized-system-id #(delete-app (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-app-shredder-with-hpc-system-id
  (test-hpc-app-modification #(delete-app (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-admin-app-shredder-with-invalid-system-id
  (test-unrecognized-system-id #(permanently-delete-app (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-admin-app-shredder-with-hpc-system-id
  (test-hpc-app-modification #(permanently-delete-app (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-app-job-view-with-invalid-system-id
  (test-unrecognized-system-id #(apps/get-app-job-view (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-de-app-job-view-with-invalid-app-id
  (test-non-uuid #(apps/get-app-job-view (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-deletion-with-invalid-system-id
  (test-unrecognized-system-id #(apps/delete-app (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-app-deletion-with-hpc-system-id
  (test-hpc-app-modification #(apps/delete-app (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-de-app-deletion-with-invalid-app-id
  (test-non-uuid #(apps/delete-app (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-relabling-with-invalid-system-id
  (test-unrecognized-system-id #(apps/relabel-app (get-user :testde1) fake-system-id {:id fake-app-id})))

(deftest test-app-relabling-with-hpc-system-id
  (test-hpc-app-modification #(apps/relabel-app (get-user :testde1) hpc-system-id {:id fake-app-id})))

(deftest test-de-app-relabling-with-invalid-app-id
  (test-non-uuid #(apps/relabel-app (get-user :testde1) de-system-id {:id fake-app-id})))

(deftest test-app-update-with-invalid-system-id
  (test-unrecognized-system-id #(apps/update-app (get-user :testde1) fake-system-id {:id fake-app-id})))

(deftest test-app-update-with-hpc-system-id
  (test-hpc-app-modification #(apps/update-app (get-user :testde1) hpc-system-id {:id fake-app-id})))

(deftest test-de-app-update-with-invalid-app-id
  (test-non-uuid #(apps/relabel-app (get-user :testde1) de-system-id {:id fake-app-id})))

(deftest test-app-integration-data-with-invalid-system-id
  (test-unrecognized-system-id #(apps/get-app-integration-data (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-app-integration-data-with-hpc-system-id
  (test-hpc-integration-data #(apps/get-app-integration-data (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-app-integration-data-with-invalid-app-id
  (test-non-uuid #(apps/get-app-integration-data (get-user :testde1) de-system-id fake-app-id)))

(deftest test-copy-app-with-invalid-system-id
  (test-unrecognized-system-id #(apps/copy-app (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-copy-app-with-hpc-system-id
  (test-hpc-app-modification #(apps/copy-app (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-copy-de-app-with-invalid-app-id
  (test-non-uuid #(apps/copy-app (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-details-with-invalid-system-id
  (test-unrecognized-system-id #(apps/get-app-details (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-de-app-details-with-invalid-app-id
  (test-non-uuid #(apps/get-app-details (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-docs-with-invalid-system-id
  (test-unrecognized-system-id #(apps/get-app-docs (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-de-app-docs-with-invalid-app-id
  (test-non-uuid #(apps/get-app-docs (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-favorite-removal-with-invalid-system-id
  (test-unrecognized-system-id #(apps/remove-app-favorite (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-app-favorite-removal-with-hpc-system-id
  (test-hpc-app-favorite #(apps/remove-app-favorite (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-de-app-favorite-removal-with-invalid-app-id
  (test-non-uuid #(apps/remove-app-favorite (get-user :testde1) de-system-id fake-app-id)))

(deftest test-app-favorite-with-invalid-system-id
  (test-unrecognized-system-id #(apps/add-app-favorite (get-user :testde1) fake-system-id fake-app-id)))

(deftest test-app-favorite-with-hpc-system-id
  (test-hpc-app-favorite #(apps/add-app-favorite (get-user :testde1) hpc-system-id fake-app-id)))

(deftest test-de-app-favorite-with-invalid-app-id
  (test-non-uuid #(apps/add-app-favorite (get-user :testde1) de-system-id fake-app-id)))
