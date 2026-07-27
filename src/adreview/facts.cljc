(ns adreview.facts
  "Per-jurisdiction advertising *pre-publication review* (广告审查) and
  internet-advertising conformance catalog for the China advertising
  actor. G2-style spec-basis table.

  Surface: 中华人民共和国广告法 (Advertising Law of the PRC) as amended
  2021-04-29; 互联网广告管理办法 (Measures for the Administration of
  Internet Advertising, SAMR Decree No. 72, in force 2023-05-01);
  药品、医疗器械、保健食品、特殊医学用途配方食品广告审查管理暂行办法
  (SAMR Decree No. 21, in force 2020-03-01).

  Every entry cites an official source URL that was actually fetched
  and read on `:retrieved-at`; a summary states only what the cited
  source confirms. A jurisdiction not in `catalog` has NO spec-basis
  -- the advisor must not fabricate one, and the governor holds if it
  tries. Coverage is reported HONESTLY (see `coverage`): this repo is
  the CHN member of the iso3166 family and seeds exactly one
  jurisdiction. Extending it is additive -- add one map, cite a real
  source, done.")

(def catalog
  "iso3 -> requirement map.

  `:reviewed-categories` is the CLOSED set of product categories whose
  advertisements may not be published before a 广告审查机关 has
  reviewed the creative and issued a 广告批准文号. Six of the seven
  come from 广告法第四十六条 directly; `:special-medical-food` is added
  by 互联网广告管理办法第七条, which is why the map records which
  source each came from rather than flattening them into one list."
  {"CHN"
   {:name "People's Republic of China"
    :owner-authority "国家市场监督管理总局 (State Administration for Market Regulation, SAMR)"
    :legal-basis "中华人民共和国广告法 (Advertising Law of the PRC, 1994-10-27 通过 / 2015-04-24 修订 / 2018-10-26 第一次修正 / 2021-04-29 第二次修正)"
    :national-spec "广告可识别性 (第十四条)・虚假广告禁止 (第二十八条)・广告代言人义务 (第三十八条)・发布前广告审查 (第四十六条)"
    :provenance "https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_5474cf75173c45d6a0379730fb4e8d97.html"
    :retrieved-at "2026-07-27"

    :required-evidence ["客户委托记录 (client-brief-record)"
                        "广告内容审核记录 (creative-review-record)"
                        "广告承接登记记录 (acceptance-registration-record)"
                        "媒介投放授权记录 (media-authorization-record)"]

    ;; --- pre-publication review (广告审查) ---
    :reviewed-categories #{:medical-service :drug :medical-device :pesticide
                           :veterinary-drug :health-food :special-medical-food}
    :reviewed-categories-source
    {:medical-service     "广告法第四十六条 (医疗)"
     :drug                "广告法第四十六条 (药品)"
     :medical-device      "广告法第四十六条 (医疗器械)"
     :pesticide           "广告法第四十六条 (农药)"
     :veterinary-drug     "广告法第四十六条 (兽药)"
     :health-food         "广告法第四十六条 (保健食品)"
     :special-medical-food "互联网广告管理办法第七条 (特殊医学用途配方食品)"}
    :review-authority "各省、自治区、直辖市市场监督管理部门・药品监督管理部门 (广告审查机关, 暂行办法第四条)"
    :review-legal-basis "药品、医疗器械、保健食品、特殊医学用途配方食品广告审查管理暂行办法 (国家市场监督管理总局令第21号, 2019-12-24 公布, 2020-03-01 施行)"
    :review-provenance "https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_4ca076ac0eeb4a9e9204e0cee26e359b.html"
    ;; 暂行办法第九条: 广告应当显著标明广告批准文号.
    ;; 暂行办法第十八条: 批准文号有效期与产品注册证明文件等中最短的有效期一致;
    ;; 产品文件未规定有效期的, 批准文号有效期为两年.
    :approval-number-article "暂行办法第九条 (显著标明广告批准文号)"
    :approval-validity-article "暂行办法第十八条 (有效期は产品注册证明文件等の最短有効期に一致、未規定なら2年)"

    ;; --- internet advertising (互联网广告管理办法) ---
    :internet-legal-basis "互联网广告管理办法 (国家市场监督管理总局令第72号, 2023-02-25 公布, 2023-05-01 施行)"
    :internet-provenance "https://www.samr.gov.cn/zw/zfxxgk/fdzdgknr/fgs/art/2023/art_d93a579afd45413e8576e4623fab348f.html"
    :internet-labelling-article "互联网广告管理办法第九条 (可识别性・竞价排名は显著标明\"广告\"、与自然搜索结果明显区分)"
    :internet-popup-article "互联网广告管理办法第十条 (弹出广告は显著标明关闭标志、确保一键关闭)"
    :internet-recordkeeping-article "互联网广告管理办法第十四条 (广告承接登记・审核・档案管理制度と广告审核人员の配备)"

    ;; --- endorser (广告代言人) ---
    :endorser-legal-basis "广告法第三十八条 (代言人は未使用の商品・未受のサービスを推荐・证明してはならない; 不满十周岁の未成年者は代言人になれない)"
    :endorser-minimum-age 10

    ;; --- penalties (context only; this actor never assesses a fine) ---
    :false-advertising-article "广告法第二十八条 (虚假广告の定义)"
    :penalty-note "广告法第五十五条: 初回は广告费用の3倍以上5倍以下の罚款、情节严重は5倍以上10倍以下および吊销营业执照"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to publish on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing
  jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-iso3166-chn-advertising R0: " (count catalog)
                 " jurisdiction seeded (the CHN member of the iso3166 "
                 "family). Extend `adreview.facts/catalog`, never "
                 "fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` satisfy every evidence item listed for `iso3`?
  Missing spec-basis -> never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn requires-ad-review?
  "Does `category` fall inside `iso3`'s CLOSED set of categories that
  may not be published before 广告审查? An UNKNOWN jurisdiction or an
  unknown category is not silently 'no' to the caller -- it is `false`
  here, and `adreview.governor` independently refuses to publish
  anything whose jurisdiction has no spec-basis at all, so an unknown
  jurisdiction can never reach the publish gate on this answer."
  [iso3 category]
  (boolean (some-> (spec-basis iso3) :reviewed-categories (contains? category))))

(defn review-basis
  "The citation trio a 广告审查 proposal must carry, or nil."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:review-authority sb)
      (select-keys sb [:review-authority :review-legal-basis :review-provenance
                       :approval-number-article :approval-validity-article]))))

(defn internet-basis
  "The citation trio an internet-advertising conformance proposal must
  carry, or nil."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:internet-legal-basis sb)
      (select-keys sb [:internet-legal-basis :internet-provenance
                       :internet-labelling-article :internet-popup-article
                       :internet-recordkeeping-article]))))

(defn endorser-minimum-age
  "The jurisdiction's own minimum endorser age, or nil when the
  jurisdiction has no spec-basis. Never defaulted to a guess."
  [iso3]
  (:endorser-minimum-age (spec-basis iso3)))
