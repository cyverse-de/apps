(ns apps.service.apps.de.jobs.resources
  (:require [apps.util.config :as config]))

(defn- get-min-resource-setting
  "Determines the minimum amount of a specific resource required for the container.
   When allow-zero? is true, zero is treated as a valid request value (useful for GPUs).
   When false (default), zero is treated as invalid/unspecified."
  ([min-kw max-kw default container requirements]
   (get-min-resource-setting min-kw max-kw default container requirements false))
  ([min-kw max-kw default container requirements allow-zero?]
   (let [c-min (get container min-kw)
         c-max (get container max-kw)
         r-min (get requirements min-kw)
         r-max (get requirements max-kw)]
     (cond
       ;; A nonexistent or nonsensical request defaults to the minimum defined for the container, which may be nil.
       (or (nil? r-min) (and (not allow-zero?) (zero? r-min)) (neg? r-min))
       c-min

       ;; Zero is a valid request when allow-zero? is true (explicitly requesting none)
       (and allow-zero? (zero? r-min))
       (or c-min 0)

       ;; If the requested minimum is less than the container minimum, default to the container's minimum setting.
       (and c-min (< r-min c-min))
       c-min

       ;; In all other cases, the requested minimum must not be greater than the requested or container maximum.
       :else
       (apply min (filter (if allow-zero? some? (every-pred some? pos?))
                          [r-min r-max (or c-max default)]))))))

(defn- get-max-resource-setting
  "Determines the maximum amount of a specific resource required for the container.
   When allow-zero? is true, zero is treated as a valid request value (useful for GPUs).
   When false (default), zero is treated as invalid/unspecified."
  ([max-kw default min-resource-setting-fn container requirements]
   (get-max-resource-setting max-kw default min-resource-setting-fn container requirements false))
  ([max-kw default min-resource-setting-fn container requirements allow-zero?]
   (let [c-max (get container max-kw)
         r-max (get requirements max-kw)]
     (cond
       ;; If the requested maximum wasn't specified or invalid, use the requested minimum.
       (or (nil? r-max) (and (not allow-zero?) (zero? r-max)) (neg? r-max))
       (min-resource-setting-fn container requirements)

       ;; Zero is valid when allow-zero? is true - use the minimum (which may also be zero)
       (and allow-zero? (zero? r-max))
       (min-resource-setting-fn container requirements)

       ;; If the requested maximum was specified, it must not be greater than the container maximum.
       :else
       (min (or c-max default) r-max)))))

(defn get-required-memory
  "Determines the minimum amount of memory required for the container."
  [container requirements]
  (get-min-resource-setting :min_memory_limit :memory_limit (config/default-memory-limit) container requirements))

(defn get-max-memory
  "Determines the maximum amount of memory that may be consumed by the app."
  [container requirements]
  (get-max-resource-setting :memory_limit (config/default-memory-limit) get-required-memory container requirements))

(defn get-required-cpus
  "Determines the minimum number of CPUs required for the container."
  [container requirements]
  (get-min-resource-setting :min_cpu_cores :max_cpu_cores (config/default-cpu-limit) container requirements))

(defn get-max-cpus
  "Determines the maximum amount of CPUs that may be used by the app."
  [container requirements]
  (get-max-resource-setting :max_cpu_cores (config/default-cpu-limit) get-required-cpus container requirements))

(defn get-required-gpus
  "Determines the minimum number of GPUs required for the container."
  [container requirements]
  (get-min-resource-setting :min_gpus :max_gpus (config/default-gpu-limit) container requirements true))

(defn get-max-gpus
  "Determines the maximum number of GPUs that may be used by the app."
  [container requirements]
  (get-max-resource-setting :max_gpus (config/default-gpu-limit) get-required-gpus container requirements true))

(defn get-required-disk-space
  "Determines the minimum amount of disk space required for the container."
  [container requirements]
  (get-min-resource-setting :min_disk_space :min_disk_space (config/default-disk-limit) container requirements))
