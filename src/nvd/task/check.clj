;; The MIT License (MIT)
;;
;; Copyright (c) 2016- Richard Hull
;;
;; Permission is hereby granted, free of charge, to any person obtaining a copy
;; of this software and associated documentation files (the "Software"), to deal
;; in the Software without restriction, including without limitation the rights
;; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
;; copies of the Software, and to permit persons to whom the Software is
;; furnished to do so, subject to the following conditions:
;;
;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.
;;
;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
;; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
;; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
;; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
;; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
;; SOFTWARE.

(ns nvd.task.check
  (:require
   [clansi :refer [style]]
   [clojure.java.io :as io]
   [clojure.string :as s]
   [nvd.config :refer [default-edn-config-filename with-config]]
   [nvd.report :refer [fail-build? generate-report print-summary]]
   [trptcolin.versioneer.core :refer [get-version]])
  (:import
   (java.io File)
   (org.owasp.dependencycheck Engine)
   (org.owasp.dependencycheck.exception ExceptionCollection)))

(def version
  (delay {:nvd-clojure (get-version "nvd-clojure" "nvd-clojure")
          :dependency-check (.getImplementationVersion (.getPackage Engine))}))

(def classpath-separator-re
  (re-pattern (str File/pathSeparatorChar)))

(defn absolute-path ^String [file]
  (s/replace-first file #"^~" (System/getProperty "user.home")))

(defn parse-classpath
  "Accepts a classpath string (i.e. colon-separated paths) and returns a sequence of analyzable
  absolute paths.

  In particular, source paths such as `src`, while part of the classpath, won't be meaningfully
  analyzed by dependency-check-core. We only care about regular files (e.g. *.jar or
  package-lock.json). Thus, skip directories in general as well as non-existing files."
  [classpath-string]
  (into []
        (comp (remove (fn [^String s]
                        (let [file (io/file s)]
                          (or (.isDirectory file)
                              (not (.exists file))))))
              (map absolute-path))
        (s/split classpath-string classpath-separator-re)))

(defn- scan-and-analyze [project]
  (let [^Engine engine (:engine project)]
    ;; See `parse-classpath` for details on which classpath entries are considered here.
    (doseq [p (:classpath project)]
      (.scan engine (absolute-path p)))
    (try
      (.analyzeDependencies engine)
      (catch ExceptionCollection e
        (println "Encountered errors while analyzing:" (.getMessage e))
        (doseq [exc (.getExceptions e)]
          (println exc))
        (let [exception-info (ex-info (str `ExceptionCollection)
                                      {:exceptions (.getExceptions e)})]
          (throw exception-info))))
    project))

(defn conditional-exit [{:keys [exit-after-check failed?]
                         {:keys [throw-if-check-unsuccessful?]} :nvd
                         :as project}]
  (cond
    (and failed? throw-if-check-unsuccessful?)
    (throw (ex-info "nvd-clojure failed / found vulnerabilities" {}))

    exit-after-check
    (System/exit (if failed? -1 0))

    :else project))

(defn jvm-version []
  (as-> (System/getProperty "java.version") $
    (s/split $ #"\.")
    (take 2 $)
    (s/join "." $)
    (Double/parseDouble $)))

(defn impl [config-filename classpath]
  (with-config [project config-filename]
    (println "Checking dependencies for" (-> project
                                             :title
                                             (s/trim)
                                             (str "...")
                                             (style :bright :yellow)))
    (println "  using nvd-clojure:" (:nvd-clojure @version) "and dependency-check:" (:dependency-check @version))
    (-> project
        (assoc :classpath classpath)
        scan-and-analyze
        generate-report
        print-summary
        fail-build?
        conditional-exit)))

(defn -main [& [config-filename ^String classpath-string]]
  (when (s/blank? classpath-string)
    (throw (ex-info "nvd-clojure requires a classpath value to be explicitly passed as a CLI argument.
Older usages are deprecated." {})))

  (let [classpath (parse-classpath classpath-string)]

    (when (empty? classpath)
      (throw (ex-info "No entries in given classpath qualify for analysis.

Note that only regular files (non-directories) are considered."
                      {:classpath classpath-string})))

    (when-not (System/getProperty "nvd-clojure.internal.skip-self-check")
      (when-let [bad-entry (->> classpath
                                (some (fn [^String entry]
                                        (and (-> entry (.endsWith ".jar"))
                                             (when (or (-> entry (.contains "dependency-check-core"))
                                                       (-> entry (.contains "nvd-clojure")))
                                               entry)))))]
        (throw (ex-info "nvd-clojure should not analyse itself. This typically indicates a badly setup integration.

Please refer to the project's README for recommended usages."
                        {:bad-entry bad-entry
                         :classpath classpath-string}))))

    ;; specifically handle blank strings (in addition to nil)
    ;; so that CLI callers can skip the first argument by simply passing an empty string:
    (let [config-filename (if (s/blank? config-filename)
                            default-edn-config-filename
                            config-filename)]
      (impl config-filename classpath))))
