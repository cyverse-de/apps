(ns apps.service.apps.apps-test
  (:use [apps.service.apps.test-utils :only [get-user fake-system-id hpc-system-id delete-app permanently-delete-app]]
        [clojure.test])
  (:require [apps.persistence.jobs :as jp]
            [apps.service.apps :as apps]
            [apps.service.apps.test-fixtures :as atf]
            [apps.test-fixtures :as tf])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)

(defn- test-unrecognized-system-id [f]
  (is (thrown-with-msg? ExceptionInfo #"unrecognized system ID" (f))))

(defn- test-hpc-app-modification [f]
  (is (thrown-with-msg? ExceptionInfo #"Cannot add or modify HPC apps" (f))))

(deftest test-app-addition-with-invalid-system-id
  (test-unrecognized-system-id #(apps/add-app (get-user :testde1) fake-system-id atf/app-definition)))

(deftest test-app-addition-with-hpc-system-id
  (test-hpc-app-modification #(apps/add-app (get-user :testde1) hpc-system-id atf/app-definition)))

(deftest test-command-preview-with-invalid-system-id
  (test-unrecognized-system-id #(apps/preview-command-line (get-user :testde1) fake-system-id atf/app-definition)))

(deftest test-command-preview-with-hpc-system-id
  (test-hpc-app-modification #(apps/preview-command-line (get-user :testde1) hpc-system-id atf/app-definition)))

(deftest test-app-shredder-with-invalid-system-id
  (test-unrecognized-system-id #(delete-app (get-user :testde1) fake-system-id "fakeid")))

(deftest test-app-shredder-with-hpc-system-id
  (test-hpc-app-modification #(delete-app (get-user :testde1) hpc-system-id "fakeid")))

(deftest test-admin-app-shredder-with-invalid-system-id
  (test-unrecognized-system-id #(permanently-delete-app (get-user :testde1) fake-system-id "fakeid")))

(deftest test-admin-app-shredder-with-hpc-system-id
  (test-hpc-app-modification #(permanently-delete-app (get-user :testde1) hpc-system-id "fakeid")))
