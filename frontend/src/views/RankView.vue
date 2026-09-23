<script setup>
import { computed, ref } from "vue";
import GkHeader from "../components/GkHeader.vue";

/* 排名数据来源：软科中国大学排名（软科官方榜单入库，soft_ranking 字段，可溯源） */
const TYPE_TABS = ["全部", "综合", "理工", "医药", "师范", "财经", "农林", "政法", "语言", "民族", "体育", "军事", "艺术"];

const items = ref([]);
const loading = ref(true);
const loadError = ref("");
const activeType = ref("全部");
const page = ref(1);
const pageSize = 50;

async function load() {
  loading.value = true;
  loadError.value = "";
  try {
    const res = await fetch("/api/universities/ranking");
    if (!res.ok) throw new Error("HTTP " + res.status);
    items.value = (await res.json()) || [];
  } catch (ex) {
    loadError.value = "榜单加载失败：" + (ex?.message || ex);
  } finally {
    loading.value = false;
  }
}

load();

const filtered = computed(() => {
  if (activeType.value === "全部") return items.value;
  return items.value.filter((s) => s.schoolType === activeType.value);
});

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)));
const paged = computed(() => {
  const start = (page.value - 1) * pageSize;
  return filtered.value.slice(start, start + pageSize);
});

function pickType(tab) {
  activeType.value = tab;
  page.value = 1;
}

function tagList(school) {
  return school.schoolTags || [];
}
</script>

<template>
  <div class="gk-page">
    <GkHeader active="院校排行" />

    <main class="gk-home__container gk-page__main">
      <div class="gk-page__body">
        <section class="gk-page__content gk-rankboard">
          <header class="gk-rankboard__head">
            <div>
              <h2>院校排行</h2>
              <p class="gk-rankboard__source">排名来源：软科中国大学排名（2025，软科官网公开榜单入库），仅展示有可追溯排名的院校；排名并列时按院校代码排序。</p>
            </div>
            <div class="gk-rankboard__tabs">
              <button
                v-for="tab in TYPE_TABS"
                :key="tab"
                type="button"
                :class="{ 'is-active': activeType === tab }"
                @click="pickType(tab)"
              >
                {{ tab }}
              </button>
            </div>
          </header>

          <p v-if="loadError" class="gk-rankboard__error">{{ loadError }}</p>
          <p v-else-if="loading" class="gk-rankboard__loading">榜单加载中…</p>
          <el-empty v-else-if="!filtered.length" description="当前类型暂无可追溯排名的院校" />

          <table v-else class="gk-rankboard__table">
            <thead>
              <tr>
                <th class="gk-rankboard__col-rank">排名</th>
                <th>院校</th>
                <th>省份</th>
                <th>类型</th>
                <th>层次标签</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="school in paged" :key="school.id">
                <td class="gk-rankboard__col-rank">{{ school.softRanking }}</td>
                <td>
                  <a :href="`/#/schools/${school.id}`" class="gk-rankboard__name">{{ school.name }}</a>
                </td>
                <td>{{ school.province || "—" }}</td>
                <td>{{ school.schoolType || "—" }}</td>
                <td>
                  <span
                    v-for="tag in tagList(school)"
                    :key="tag"
                    class="gk-rankboard__tag"
                  >{{ tag }}</span>
                </td>
              </tr>
            </tbody>
          </table>

          <div v-if="!loading && filtered.length" class="gk-rankboard__pager">
            <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
            <span>{{ page }} / {{ totalPages }}</span>
            <button type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
            <em>共 {{ filtered.length }} 所</em>
          </div>
        </section>

      </div>
    </main>
  </div>
</template>

<style scoped>
.gk-rankboard__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.gk-rankboard__head h2 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: #1f2329;
}
.gk-rankboard__source {
  margin: 0;
  font-size: 12px;
  color: #8a94a6;
  max-width: 560px;
}
.gk-rankboard__tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.gk-rankboard__tabs button {
  border: 1px solid #e5e8ef;
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  color: #5b6472;
  cursor: pointer;
}
.gk-rankboard__tabs button.is-active {
  background: #ff6600;
  border-color: #ff6600;
  color: #fff;
  font-weight: 600;
}
.gk-rankboard__error,
.gk-rankboard__loading {
  padding: 24px 0;
  text-align: center;
  color: #8a94a6;
  font-size: 13px;
}
.gk-rankboard__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.gk-rankboard__table th,
.gk-rankboard__table td {
  padding: 9px 12px;
  border-bottom: 1px solid #f0f2f6;
  text-align: left;
  color: #2b3245;
}
.gk-rankboard__table th {
  background: #f7f9fc;
  color: #5b6472;
  font-weight: 600;
}
.gk-rankboard__col-rank {
  width: 64px;
  font-weight: 700;
  color: #ff6600;
}
.gk-rankboard__name {
  color: #1f2329;
  text-decoration: none;
  font-weight: 600;
}
.gk-rankboard__name:hover {
  color: #ff6600;
}
.gk-rankboard__tag {
  display: inline-block;
  margin-right: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #fff4ec;
  color: #d4611a;
  font-size: 11px;
}
.gk-rankboard__pager {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: center;
  padding: 14px 0 2px;
  font-size: 13px;
  color: #5b6472;
}
.gk-rankboard__pager button {
  border: 1px solid #e5e8ef;
  background: #fff;
  border-radius: 8px;
  padding: 4px 14px;
  font-size: 12px;
  cursor: pointer;
}
.gk-rankboard__pager button:disabled {
  color: #c2c8d2;
  cursor: default;
}
.gk-rankboard__pager em {
  font-style: normal;
  color: #8a94a6;
}
</style>
