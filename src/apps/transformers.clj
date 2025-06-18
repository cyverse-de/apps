(ns apps.transformers)

(defn param->long
  "Converts a String or a Number to a long."
  [param]
  (try
    (if (number? param)
      (long param)
      (Long/parseLong param))
    (catch NumberFormatException e
      (throw (IllegalArgumentException. e)))))
