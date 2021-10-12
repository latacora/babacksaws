(ns com.latacora.awsvault-cred-provider-test
  (:require
   [clojure.test :as t]
   [cognitect.aws.credentials :as awscreds]
   [clojure.string :as str]
   [com.latacora.awsvault-cred-provider :as p]
   [meander.epsilon :as m]
   [clojure.java.shell :as sh])
  (:import
   (java.time Instant)))

(defn fake-env-vars
  [profile]
  {"AWS_ACCESS_KEY_ID" "ASIA"
   "AWS_SECRET_ACCESS_KEY" "xyzzy"
   "AWS_SESSION_TOKEN" (str "iddqd" profile)
   "AWS_SESSION_EXPIRATION" (-> (Instant/now) (.plusSeconds 3600) str)})

(defn fake-env-output
  [profile]
  (->>
   (fake-env-vars profile)
   (map (partial str/join "="))
   (str/join "\0")))

(defn fake-sh!
  [ctx & args]
  (let [expected-profile (->> @ctx (filter #(-> % :type (= :profile))) last :value)
        profile (m/match args
                  ("aws-vault" "exec" ?profile "--" "env" "-0")
                  ?profile)]
    (t/is (= expected-profile profile))
    (swap! ctx conj {:type :call :fn 'fake-sh :args args})
    {:out (fake-env-output profile)}))

(t/deftest e2e-test
  (let [profile (str (gensym))
        ctx (atom [{:type :profile :value profile}])
        n-calls (fn [] (->> @ctx (filter #(-> % :type (= :call))) count))]
    (with-redefs [sh/sh (partial fake-sh! ctx)]
      (let [provider (p/aws-vault-provider profile)]
        (t/testing "instantiating does not fetch creds"
          (t/is (= 0 (n-calls))))

        (t/testing "first fetch calls aws-vault"
          (let [result (awscreds/fetch provider)
                {:aws/keys [access-key-id secret-access-key session-token]} result]
            (t/is (= "ASIA" access-key-id))
            (t/is (= "xyzzy" secret-access-key))
            (t/is (= (str "iddqd" profile) session-token))
            (t/is (<= 3000 (::awscreds/ttl result) 3600))
            (t/is (= 1 (n-calls)))))

        (t/testing "fetch results are cached"
          ;; Not entirely sure if this behavior is necessary. aws-vault should
          ;; have its own caching.
          (awscreds/fetch provider)
          (t/is (= 1 (n-calls))))))))
