(ns apps.persistence.util
  (:use [korma.db :only [transaction *current-conn*]])
  (:require [korma.core :as sql]))

(defn sql-array
  "Returns a SQL ARRAY(...) object,
   typically for use with a large (>32k) list of items that need to be passed to a SQL function.

   array-type:  the SQL name of the type of the `array-items` (e.g. 'varchar' or 'uuid').
   array-items: the elements that populate the returned SQL ARRAY object."
  [array-type array-items]
  ;; This transaction is required in order for *current-conn* to be non-nil, in case we're not already in a transaction.
  (transaction
    (.createArrayOf (:connection *current-conn*) array-type (into-array array-items))))

(defn sqlfn-any-array
  "Returns a SQL function ANY(ARRAY(...)) for use in a `where` clause, since using an `IN(...)` with a large (>32k) list
   will cause a PSQLException (I/O error) from too many query parameters.
   For example, use `(when {:id (sqlfn-any-array \"uuid\" id-list)})` instead of
   `(when {:id [in id-list]})`.

   array-type:  the SQL name of the type of the `array-items` (e.g. 'varchar' or 'uuid').
   array-items: the elements that populate the SQL ARRAY passed to the ANY function."
  [array-type array-items]
  (sql/sqlfn :any (sql-array array-type array-items)))
