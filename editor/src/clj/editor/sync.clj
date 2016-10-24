(ns editor.sync
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [editor
             [dialogs :as dialogs]
             [diff-view :as diff-view]
             [git :as git]
             [handler :as handler]
             [login :as login]
             [ui :as ui]]
            [editor.progress :as progress])
  (:import [org.eclipse.jgit.api Git PullResult]
           [org.eclipse.jgit.revwalk RevCommit]
           [org.eclipse.jgit.api.errors StashApplyFailureException]
           [javafx.scene Parent Scene]
           [javafx.scene.control SelectionMode ListView TextArea]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.stage Modality]))

(set! *warn-on-reflection* true)

(def ^:dynamic *login* true)

;; =================================================================================

;; Flow state-diagram

;; 1. Pull
;;
;;    :start -> :pulling -> ---------> :done
;;                    \          /
;;                     -> :resolve
;;                               \
;;                                -> :cancel

;; 2. Push
;;
;;    <PULL-FLOW> -> :start -> :staging -> :committing -> :pushing -> :done
;;         \                                                /  \
;;          <-----------------------------------------------    -> :cancel


(defn- serialize-ref [^RevCommit ref]
  (some->> ref .getName))

(defn- deserialize-ref [revision ^Git git]
  (some->> revision (git/get-commit (.getRepository git))))

(defn- serialize-flow [flow]
  (-> flow
      (dissoc :git)
      (dissoc :creds)
      (update :start-ref serialize-ref)
      (update :stash-ref serialize-ref)))

(defn- deserialize-flow [serialized-flow ^Git git creds]
  (-> serialized-flow
      (assoc :git git)
      (assoc :creds creds)
      (update :start-ref deserialize-ref git)
      (update :stash-ref deserialize-ref git)))

(defn- make-flow [^Git git creds start-ref stash-ref]
  {:state     :pull/start
   :git       git
   :creds     creds
   :start-ref start-ref
   :stash-ref stash-ref
   :progress  (progress/make "pull" 4)
   :conflicts {}
   :resolved  {}
   :staged    #{}
   :modified  #{}})

(defn- should-update-journal? [old-flow new-flow]
  (let [simple-keys [:state :start-ref :stash-ref :conflicts :resolved :staged :modified]]
    (or (not= (select-keys old-flow simple-keys)
              (select-keys new-flow simple-keys))
        (let [old-progress (:progress old-flow)
              new-progress (:progress new-flow)]
          (or (not= (:message old-progress) (:message new-progress))
              (and (= (:pos new-progress) (:size new-progress))
                   (not= (:pos old-progress) (:size old-progress))))))))

(defn- flow-journal-file ^java.io.File [^Git git]
  (io/file (.. git getRepository getWorkTree) ".internal/.sync-in-progress"))

(defn- write-flow-journal! [{:keys [git] :as flow}]
  (let [file (flow-journal-file git)
        data (serialize-flow flow)]
    (io/make-parents file)
    (spit file (pr-str data))))

(defn- on-flow-changed [_ _ old-flow new-flow]
  (when (should-update-journal? old-flow new-flow)
    (write-flow-journal! new-flow)))

(defn flow-in-progress? [^Git git]
  (.exists (flow-journal-file git)))

(defn begin-flow! [^Git git prefs]
  (when *login*
    (login/login prefs))
  (let [creds (git/credentials prefs)
        start-ref (git/get-current-commit-ref git)
        stash-ref (git/stash git)
        flow  (make-flow git creds start-ref stash-ref)
        !flow (atom flow)]
    (write-flow-journal! flow)
    (add-watch !flow ::on-flow-changed on-flow-changed)
    !flow))

(defn resume-flow [^Git git prefs]
  (when *login*
    (login/login prefs))
  (let [creds (git/credentials prefs)
        file  (flow-journal-file git)
        data  (with-open [reader (java.io.PushbackReader. (io/reader file))]
                (edn/read reader))
        flow  (deserialize-flow data git creds)
        !flow (atom flow)]
    (add-watch !flow ::on-flow-changed on-flow-changed)
    !flow))

(defn cancel-flow! [!flow]
  (remove-watch !flow ::on-flow-changed)
  (let [{:keys [git start-ref stash-ref]} @!flow
        file (flow-journal-file git)]
    (when (.exists file)
      (io/delete-file file :silently))
    (git/hard-reset git start-ref)
    (when stash-ref
      (git/stash-apply git stash-ref)
      (git/stash-drop git stash-ref))))

(defn finish-flow! [!flow]
  (remove-watch !flow ::on-flow-changed)
  (let [{:keys [git stash-ref]} @!flow
        file (flow-journal-file git)]
    (when (.exists file)
      (io/delete-file file :silently))
    (when stash-ref
      (git/stash-drop git stash-ref))))

(defn- tick
  ([flow new-state]
   (tick flow new-state 1))
  ([flow new-state n]
   (-> flow
       (assoc :state new-state)
       (update :progress #(progress/advance % n)))))

(defn refresh-git-state [{:keys [git] :as old-flow}]
  (let [st (git/status git)]
    (merge old-flow
           {:staged   (set/union (:added st)
                                 (:deleted st)
                                 (set/difference (:uncommited-changes st)
                                                 (:modified st)))
            :modified (set/union (:modified st)
                                 (:untracked st))})))

(defn advance-flow [{:keys [git state progress creds conflicts stash-ref message] :as flow} render-progress]
  (render-progress progress)
  (condp = state
    :pull/start     (advance-flow (tick flow :pull/pulling) render-progress)
    :pull/pulling   (let [^PullResult pull-res (try (git/pull git creds)
                                                    (catch Exception e
                                                      (println e)))]
                      (if (and pull-res (.isSuccessful pull-res))
                        (advance-flow (tick flow :pull/applying) render-progress)
                        (advance-flow (tick flow :pull/error) render-progress)))
    :pull/applying  (let [stash-res (when stash-ref
                                      (try (git/stash-apply git stash-ref)
                                           (catch StashApplyFailureException e
                                             :conflict)
                                           (catch Exception e
                                             (println e))))
                          status    (git/status git)]
                      (cond
                        (nil? stash-ref) (advance-flow (tick flow :pull/done 2) render-progress)
                        (= :conflict stash-res) (advance-flow (-> flow
                                                                  (tick :pull/conflicts)
                                                                  (assoc :conflicts (:conflicting-stage-state status)))
                                                              render-progress)
                        stash-res (advance-flow (tick flow :pull/done 2) render-progress)
                        :else (advance-flow (tick flow :pull/error) render-progress)))
    :pull/conflicts (if (empty? conflicts)
                      (advance-flow (tick flow :pull/done) render-progress)
                      flow)
    :pull/done      flow
    :pull/error     flow

    :push/start     (advance-flow (tick (refresh-git-state flow) :push/staging) render-progress)
    :push/staging   flow
    :push/comitting (do
                      (git/commit git message)
                      (advance-flow (tick flow :push/pushing) render-progress))
    :push/pushing   (do
                      (try
                        (git/push git creds)
                        (advance-flow (tick flow :push/done) render-progress)
                        (catch Exception e
                          (println e)
                          (advance-flow (tick flow :pull/start) render-progress))))
    :push/done      flow))

(ui/extend-menu ::conflicts-menu nil
                [{:label "Show diff"
                  :command :show-diff}
                 {:label "Use ours"
                  :command :use-ours}
                 {:label "Use theirs"
                  :command :use-theirs}])

(ui/extend-menu ::staging-menu nil
                [{:label "Show diff"
                  :command :show-file-diff}
                 {:label "Stage files"
                  :command :stage-file}])

(ui/extend-menu ::unstaging-menu nil
                [{:label "Show diff"
                  :command :show-file-diff}
                 {:label "Unstage files"
                  :command :unstage-file}])

(defn get-theirs [{:keys [git] :as flow} file]
  (String. ^bytes (git/show-file git file)))

(defn get-ours [{:keys [git ^RevCommit stash-ref] :as flow} file]
  (when stash-ref
    (String. ^bytes (git/show-file git file (.name stash-ref)))))

(defn resolve-file! [!flow file]
  (when-let [entry (get (:conflicts @!flow) file)]
    (git/stage-file (:git @!flow) file)
    (git/unstage-file (:git @!flow) file)
    (swap! !flow #(-> %
                      (update :conflicts dissoc file)
                      (update :resolved assoc file entry)))))

(handler/defhandler :show-diff :sync
  (enabled? [selection] (= 1 (count selection)))
  (run [selection !flow]
    (let [file   (first selection)
          ours   (get-ours @!flow file)
          theirs (get-theirs @!flow file)]
      (when (and ours theirs)
        (diff-view/make-diff-viewer (str "Theirs '" file "'") theirs
                                    (str "Ours '" file "'") ours)))))

(handler/defhandler :show-file-diff :sync
  (enabled? [selection] (= 1 (count selection)))
  (run [selection !flow]
    (let [file   (first selection)
          ours   (try (slurp (io/file (git/worktree (:git @!flow)) file)) (catch Exception _))
          theirs (try (get-theirs @!flow file) (catch Exception _))]
      (when (and ours theirs)
        (diff-view/make-diff-viewer (str "Theirs '" file "'") theirs
                                    (str "Ours '" file "'") ours)))))

(handler/defhandler :use-ours :sync
  (enabled? [selection] (pos? (count selection)))
  (run [selection !flow]
    (doseq [f selection]
      (when-let [ours (get-ours @!flow f)]
        (spit (io/file (git/worktree (:git @!flow)) f) ours)
        (resolve-file! !flow f)))))

(handler/defhandler :use-theirs :sync
  (enabled? [selection] (pos? (count selection)))
  (run [selection !flow]
    (doseq [f selection]
      (when-let [theirs (get-theirs @!flow f)]
        (spit (io/file (git/worktree (:git @!flow)) f) theirs)
        (resolve-file! !flow f)))))

(handler/defhandler :stage-file :sync
  (enabled? [selection] (pos? (count selection)))
  (run [selection !flow]
    (doseq [f selection]
      (git/stage-file (:git @!flow) f))
    (swap! !flow refresh-git-state)))

(handler/defhandler :unstage-file :sync
  (enabled? [selection] (pos? (count selection)))
  (run [selection !flow]
    (doseq [f selection]
      (git/unstage-file (:git @!flow) f))
    (swap! !flow refresh-git-state)))

;; =================================================================================

(defn open-sync-dialog [!flow]
  (let [root            ^Parent (ui/load-fxml "sync-dialog.fxml")
        pull-root       ^Parent (ui/load-fxml "sync-pull.fxml")
        push-root       ^Parent (ui/load-fxml "sync-push.fxml")
        stage           (ui/make-stage)
        scene           (Scene. root)
        dialog-controls (ui/collect-controls root [ "ok" "push" "cancel" "dialog-area" "progress-bar"])
        pull-controls   (ui/collect-controls pull-root ["conflicting" "resolved" "conflict-box" "main-label"])
        push-controls   (ui/collect-controls push-root ["changed" "staged" "message" "content-box" "main-label"])
        render-progress (fn [progress]
                          (ui/run-later
                           (ui/update-progress-controls! progress (:progress-bar dialog-controls) nil)))
        update-controls (fn [{:keys [state conflicts resolved modified staged] :as flow}]
                          (ui/run-later
                           (if (= "pull" (namespace state))
                             (do
                               (ui/title! stage "Get Remote Changes")
                               (ui/text! (:ok dialog-controls) "Pull")
                               (ui/children! (:dialog-area dialog-controls) [pull-root])
                               (ui/fill-control pull-root)
                               (.sizeToScene (.getWindow scene)))
                             (do
                               (ui/title! stage "Push Local Changes")
                               (ui/visible! (:push dialog-controls) false)
                               (ui/text! (:ok dialog-controls) "Push")
                               (ui/children! (:dialog-area dialog-controls) [push-root])
                               (ui/fill-control push-root)
                               (.sizeToScene (.getWindow scene))))
                           (condp = state
                             :pull/conflicts (do
                                               (ui/text! (:main-label pull-controls) "Resolve Conflicts")
                                               (ui/visible! (:conflict-box pull-controls) true)
                                               (ui/items! (:conflicting pull-controls) (sort (keys conflicts)))
                                               (ui/items! (:resolved pull-controls) (sort (keys resolved)))

                                               (let [button (:ok dialog-controls)]
                                                 (ui/text! button "Apply")
                                                 (ui/disable! button (not (empty? conflicts)))))
                             :pull/done      (do
                                               (ui/text! (:main-label pull-controls) "Done!")
                                               (ui/visible! (:push dialog-controls) true)
                                               (ui/visible! (:conflict-box pull-controls) false)
                                               (ui/text! (:ok dialog-controls) "Done"))
                             :pull/error      (do
                                                (ui/text! (:main-label pull-controls) "Error getting changes")
                                                (ui/visible! (:push dialog-controls) false)
                                                (ui/visible! (:conflict-box pull-controls) false)
                                                (ui/text! (:ok dialog-controls) "Close"))

                             :push/staging   (do
                                               (ui/items! (:changed push-controls) (sort modified))
                                               (ui/items! (:staged push-controls) (sort staged))
                                               (ui/disable! (:ok dialog-controls)
                                                            (or (empty? staged)
                                                                (empty? (ui/text (:message push-controls))))))

                             :push/done    (do
                                             (ui/text! (:main-label push-controls) "Done!")
                                             (ui/visible! (:content-box push-controls) false)
                                             (ui/text! (:ok dialog-controls) "Done"))

                             nil)))]
    (dialogs/observe-focus stage)
    (.initOwner stage (ui/main-stage))
    (update-controls @!flow)
    (add-watch !flow :updater (fn [_ _ _ flow]
                                (update-controls flow)))

    (ui/on-action! (:cancel dialog-controls) (fn [_]
                                               (cancel-flow! !flow)
                                               (.close stage)))
    (ui/on-action! (:ok dialog-controls) (fn [_]
                                           (cond
                                             (= "done" (name (:state @!flow)))
                                             (do
                                               (finish-flow! !flow)
                                               (.close stage))

                                             (= :push/staging (:state @!flow))
                                             (swap! !flow #(advance-flow
                                                            (merge %
                                                                   {:state   :push/comitting
                                                                    :message (ui/text (:message push-controls))})
                                                            render-progress))

                                             :else
                                             (swap! !flow advance-flow render-progress))))
    (ui/on-action! (:push dialog-controls) (fn [_]
                                             (swap! !flow #(merge %
                                                                  {:state    :push/start
                                                                   :progress (progress/make "push" 4)}))
                                             (swap! !flow advance-flow render-progress)))

    (ui/observe (.textProperty ^TextArea (:message push-controls))
                (fn [_ _ _]
                  (update-controls @!flow)))

    (let [^ListView list-view (:conflicting pull-controls)]
      (.setSelectionMode (.getSelectionModel list-view) SelectionMode/MULTIPLE)
      (ui/context! list-view :sync {:!flow !flow} list-view)
      (ui/register-context-menu list-view ::conflicts-menu)
      (ui/cell-factory! list-view (fn [e] {:text e})))
    (ui/cell-factory! (:resolved pull-controls) (fn [e] {:text e}))

    (let [^ListView list-view (:changed push-controls)]
      (.setSelectionMode (.getSelectionModel list-view) SelectionMode/MULTIPLE)
      (ui/context! list-view :sync {:!flow !flow} list-view)
      (ui/register-context-menu list-view ::staging-menu)
      (ui/cell-factory! list-view (fn [e] {:text e})))

    (let [^ListView list-view (:staged push-controls)]
      (.setSelectionMode (.getSelectionModel list-view) SelectionMode/MULTIPLE)
      (ui/context! list-view :sync {:!flow !flow} list-view)
      (ui/register-context-menu list-view ::unstaging-menu)
      (ui/cell-factory! list-view (fn [e] {:text e})))

    (.addEventFilter scene KeyEvent/KEY_PRESSED
                     (ui/event-handler event
                                       (let [code (.getCode ^KeyEvent event)]
                                         (when (= code KeyCode/ESCAPE) true
                                               (cancel-flow! !flow)
                                               (.close stage)))))

    (.initModality stage Modality/APPLICATION_MODAL)
    (.setScene stage scene)

    (try
      (ui/show-and-wait-throwing! stage)
      (catch Exception e
        (cancel-flow! !flow)
        (throw e)))))
