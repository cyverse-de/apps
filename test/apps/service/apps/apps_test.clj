(ns apps.service.apps.apps-test
  (:use [apps.service.apps.test-utils :only [get-user fake-system-id hpc-system-id]]
        [clojure.test])
  (:require [apps.persistence.jobs :as jp]
            [apps.service.apps :as apps]
            [apps.service.apps.test-fixtures :as atf]
            [apps.test-fixtures :as tf])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)

(deftest test-app-addition-with-invalid-system-id
  (is (thrown-with-msg? ExceptionInfo #"unrecognized system ID"
                        (apps/add-app (get-user :testde1) fake-system-id atf/app-definition))))

(deftest test-app-addition-with-hpc-system-id
  (is (thrown-with-msg? ExceptionInfo #"Cannot add or modify HPC apps"
                        (apps/add-app (get-user :testde1) hpc-system-id atf/app-definition))))

(deftest test-command-preview-with-invalid-system-id
  (is (thrown-with-msg? ExceptionInfo #"unrecognized system ID"
                        (apps/preview-command-line (get-user :testde1) fake-system-id atf/app-definition))))

(deftest test-command-preview-with-hpc-system-id
  (is (thrown-with-msg? ExceptionInfo #"Cannot add or modify HPC apps"
                        (apps/preview-command-line (get-user :testde1) hpc-system-id atf/app-definition))))
