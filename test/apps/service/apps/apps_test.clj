(ns apps.service.apps.apps-test
  (:use [apps.service.apps.test-utils :only [get-user]]
        [clojure.test])
  (:require [apps.persistence.jobs :as jp]
            [apps.service.apps :as apps]
            [apps.service.apps.test-fixtures :as atf]
            [apps.test-fixtures :as tf])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :once tf/run-integration-tests tf/with-test-db tf/with-config atf/with-workspaces)

(def fake-system-id "notreal")
(def hpc-system-id jp/agave-client-name)
(def de-system-id jp/de-client-name)

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
