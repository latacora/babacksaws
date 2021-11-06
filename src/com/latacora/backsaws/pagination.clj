(ns com.latacora.backsaws.pagination
  (:require
   [cognitect.aws.client.api :as aws]
   [meander.epsilon :as m]
   [clojure.set :as set]))

(def ^:private next-markers
  #{:NextToken :NextMarker})

(def ^:private marker-keys
  (into #{:StartingToken} next-markers))

(def ^:private is-truncated-keys
  #{:IsTruncated})

(defn ^:private word-parts
  [k]
  (->> k name (re-seq #"[A-Z][a-z]*")))

(defn ^:private none-of?*
  [ks]
  (->
   (apply some-fn ks)
   (complement)
   (with-meta {::none-of (set ks)})))

(defn ^:private mapcat-ks*
  "Returns a fn that mapcats its argument over ks.

  Really only exists to make testing easier; see testing ns for details."
  [ks]
  (-> (fn [resp] (mapcat resp ks)) (with-meta {::mapcat-of ks})))

(defn ^:private some-fn*
  "Like `(apply some-fn ks)`.

  Really only exists to make testing easier; see testing ns for details."
  [ks]
  (-> (apply some-fn ks) (with-meta {::some-of ks})))

(defn ^:private constantly*
  "Like [[constantly]].

  Really only exists to make testing easier; see testing ns for details."
  [x]
  (-> (constantly x) (with-meta {::constantly x})))

(defn ^:private next-op-map-from-mapping
  [marker-mapping]
  (->
   (fn [op-map response]
     (->>
      (for [[resp-k req-k] marker-mapping]
        [req-k (resp-k response)])
      (into op-map)))
   (with-meta {::marker-mapping marker-mapping})))

(defn ^:private one-or-concat
  [[k :as ks]]
  (case (count ks)
    ;; [0, 1] cases optional, but benefit introspection
    0 ::not-paginated
    1 k
    (mapcat-ks* ks)))

(defn ^:private similarity
  "Find the length of the longest common substring length between two strings (or
  anything named, so also keywords and symbols)."
  [str1 str2]
  (loop [s1 (seq (name str1))
         s2 (seq (name str2))
         len 0
         maxlen 0]
    (cond
      (>= maxlen (count s1))
      maxlen

      (>= maxlen (+ (count s2) len))
      (recur (rest s1) (seq (name str2)) 0 maxlen)

      :else (let [a (nth s1 len "")
                  [b & s2] s2
                  len (inc len)]
              (if (= a b)
                (recur s1 s2 len (max len maxlen))
                (recur s1 s2 0 maxlen))))))

(declare remove-phantom-results)

(defn ^:private infer-paging-opts*
  "Like infer-paging-opts, but not memoized."
  ([client op]
   (infer-paging-opts* client op {}))
  ([client op paging-opts]
   ;; Almost always, we'll find exactly one key (keyword, really) for a given
   ;; result, but a handful of functions have two, e.g. s3's
   ;; ListObjectVersions (which has both versions and deletions). When that
   ;; happens, we run down both and merge them; it's up to the caller to
   ;; distinguish (in all cases we've seen, this is reasonable). In the common
   ;; case where there's exactly 1 kw we find, we just return that; this
   ;; promotes introspection.
   (let [{:keys [request response]} (-> client aws/ops op)
         sort-similar (partial sort-by #(- (similarity op %)))]
     (->>
      (reduce
       (fn [opts [key default-fn]]
         (if-not (key opts)
           (assoc opts key (default-fn opts))
           opts)) ;; already set, leave it alone
       paging-opts
       ;; we use a vec because the order in which we compute these matters; the
       ;; definition of :truncated? depends on that of :next-marker.
       [[:results
         (fn [_]
           (-> response (m/search {?k [:seq-of _]} ?k) sort one-or-concat))]
        [:next-op-map
         (fn [_]
           (let [resp-keys (-> response keys set)
                 matches #(for [c resp-keys :when (re-matches % (name c))] c)
                 next-markers (or
                               (seq (set/intersection resp-keys next-markers))
                               (seq (matches #"Next([A-Za-z]*)*?Marker"))
                               (seq (matches #"([A-Za-z]*)*?Marker")))]
             (if-not next-markers
               ::not-paginated
               (let [req-keys (-> request keys set)]
                 (->>
                  (for [next-marker next-markers
                        :let [similarity (partial similarity next-marker)]]
                    [next-marker (apply max-key similarity req-keys)])
                  (into {})
                  next-op-map-from-mapping)))))]
        [:truncated?
         (fn [{:keys [next-op-map]}]
           (if (identical? next-op-map ::not-paginated)
             (constantly* false)
             (or
              (->> is-truncated-keys (filter response) first)
              (->> next-op-map meta ::marker-mapping keys none-of?*))))]])
      (remove-phantom-results op)))))

(defn ^:private remove-phantom-results
  "Automatically determining the pagination opts may produce additional
  results (see [[paginated-invoke]]) that are not real results. This filters
  those out. It does that by comparing results against next markers."
  [op {:keys [results next-op-map] :as paging-opts}]
  (let [results-ks (-> results meta ::mapcat-of)
        next-marker-ks (-> next-op-map meta ::marker-mapping keys)]
    (if (or
         (nil? results-ks)
         (= (count results-ks) (count next-marker-ks)))
      paging-opts
      (->>
       results-ks
       (sort-by
        (fn [results-key]
          (->> next-marker-ks
               (map (partial similarity results-key))
               (reduce max)
               (-))))
       (take (count next-marker-ks))
       sort
       one-or-concat
       (assoc paging-opts :results)))))

(def infer-paging-opts
  "For a given client + op, infer paging opts for [[paginated-invoke]]."
  (memoize infer-paging-opts*))

(defn paginated-invoke
  "Like [[aws/invoke]], but with pagination. Returns a lazy seq of results.

  You likely don't need to specify your own pagination behavior: if you don't,
  we'll try to infer some reasonable defaults. Pagination opts are as follows:

  `:results` is a fn from a response to the actual objects, e.g. :Accounts
  or (comp :Instances :Reservation). Note that because we're returning a lazy
  seq of results, we can only collect one thing at a time. Some operations, e.g.
  s3's ListObjectVersions, have two: DeletionMarkers and Versions. By default,
  these get intermingled via concatenation.

  `:truncated?` is a fn that given a response, tells you if there's another one
  or not. For some services, this is :IsTruncated (hence the name), but it is
  often based on the existence of next marker keys in the previous response.

  `:next-op-map` takes the last op map (request) and last response and returns
  the next one."
  ([client op-map]
   (let [paging-opts (infer-paging-opts client (:op op-map))]
     (paginated-invoke client op-map paging-opts)))
  ([client op-map paging-opts]
   (let [{:keys [results truncated? next-op-map]} paging-opts
         response (aws/invoke client op-map)]
     (if (truncated? response)
       (lazy-cat
        (results response)
        (let [op-map (next-op-map op-map response)]
          (paginated-invoke client op-map paging-opts)))
       (results response)))))
