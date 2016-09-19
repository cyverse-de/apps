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
