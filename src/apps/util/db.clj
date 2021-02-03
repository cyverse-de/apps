(ns apps.util.db
  "This namespace contains some database utility functions. For the time being, these functions will use Korma to
   obtain database connections. This code will have to be modified when Korma is removed."
  (:require [apps.util.conversions :refer [date->timestamp]]
            [korma.core :as sql]
            [korma.db :as db]))

(defmacro transaction
  "Executes queries within the body in a single transaction. Nested transactions are absorbed into the outermost
   transaction. For now, this is just an alias to korma.db/transaction. When Korma is removed, it will be necessary
   to come up with an alternative implementation."
  [& body]
  `(db/transaction ~@body))

(defmacro with-transaction
  "Executes queries within the body in a single transaction. Nested transactions are absorbed into the outermost
   transaction. This function is similar to apps.util.db/transaction, except that an explicit variable binding
   is created in addition to the usual implicit variable binding."
  [[tx] & body]
  `(transaction
    (let [~tx db/*current-conn*]
      ~@body)))

(defn sql-array
  "Returns a SQL ARRAY(...) object,
   typically for use with a large (>32k) list of items that need to be passed to a SQL function.

   array-type:  the SQL name of the type of the `array-items` (e.g. 'varchar' or 'uuid').
   array-items: the elements that populate the returned SQL ARRAY object."
  [array-type array-items]
  (transaction
   (.createArrayOf (:connection db/*current-conn*) array-type (into-array array-items))))

(defn sqlfn-any-array
  "Returns a SQL function ANY(ARRAY(...)) for use in a `where` clause, since using an `IN(...)` with a large (>32k) list
   will cause a PSQLException (I/O error) from too many query parameters.  For example, use `(when {:id (sqlfn-any-array
   \"uuid\" id-list)})` instead of `(when {:id [in id-list]})`.

   array-type:  the SQL name of the type of the `array-items` (e.g. 'varchar' or 'uuid').
   array-items: the elements that populate the SQL ARRAY passed to the ANY function."
  [array-type array-items]
  (sql/sqlfn :any (sql-array array-type array-items)))

(defn add-date-limits-where-clause
  [query {:keys [start_date end_date]}]
  (cond
    (and start_date end_date)
    (sql/where query {:end_date [between [(date->timestamp start_date) (date->timestamp end_date)]]})

    (and (not start_date) end_date)
    (sql/where query {:end_date [<= (date->timestamp end_date)]})

    (and (not end_date) start_date)
    (sql/where query {:end_date [>= (date->timestamp start_date)]})

    :else
    query))
