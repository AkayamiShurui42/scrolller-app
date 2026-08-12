const $ = id => document.getElementById(id);

const state = {
  scope: 'POSTS',
  sort: localStorage.getItem('sort') || 'HOT',
  media: localStorage.getItem('media') || 'ALL',
  category: localStorage.getItem('category') || 'ALL',
  nsfw: localStorage.getItem('nsfw') || 'ALL',
  query: localStorage.getItem('query') || '',
  posts: [], rawPosts: [], history: [], activeIndex: 0,
  currentLabel: 'Feed', account: null
};

const pending = new Map();
let requestSeq = 0;
let videoObserver = null;

window.__nativeMediaResult = (id, ok, body, status) => {
  const item = pending.get(id);
  if (!item) return;
  pending.delete(id);
  if (!ok) item.reject(new Error(`HTTP ${status || 0}: ${body || 'request failed'}`));
  else item.resolve({ body, status });
};

function nativePost(url, body, headers = {}) {
  if (!window.NativeMedia) return fetch(url, { method:'POST', headers, body, credentials:'include' }).then(async r => ({ body:await r.text(), status:r.status }));
  return new Promise((resolve, reject) => {
    const id = `p${Date.now()}_${++requestSeq}`;
    pending.set(id, { resolve, reject });
    NativeMedia.postJson(id, url, body, JSON.stringify(headers));
  });
}

function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => el.classList.add('hidden'), 2600);
}
function setBusy(on, text='Loading…') { $('busy').textContent=text; $('busy').classList.toggle('hidden', !on); }
function openSheet(id) { closeSheets(); $(id).classList.remove('hidden'); }
function closeSheets() { document.querySelectorAll('.sheet').forEach(s => s.classList.add('hidden')); }
function currentVisibleIndex() {
  const f=$('feed');
  if(!f || !state.posts.length) return 0;
  return Math.max(0, Math.min(state.posts.length-1, Math.round(f.scrollTop / Math.max(1,f.clientHeight))));
}
function snapshot() {
  return {scope:state.scope,sort:state.sort,media:state.media,category:state.category,nsfw:state.nsfw,query:state.query,posts:state.posts,rawPosts:state.rawPosts,activeIndex:currentVisibleIndex(),currentLabel:state.currentLabel};
}
function pushHistory(){ if(state.posts.length) state.history.push(snapshot()); if(state.history.length>16) state.history.shift(); }
function restore(s){ Object.assign(state,s); syncControls(); renderFeed(); setTimeout(()=>scrollToIndex(s.activeIndex||0,false),40); }
window.ScrolllerNativeBack = function(){
  const open=[...document.querySelectorAll('.sheet')].find(s=>!s.classList.contains('hidden'));
  if(open){ closeSheets(); return true; }
  if(state.history.length){ restore(state.history.pop()); return true; }
  return false;
};

const QUERIES = {
  SubredditQuery:`query SubredditQuery($url:String!,$iterator:String,$sortBy:GallerySortBy,$filter:GalleryFilter,$limit:Int!){getSubreddit(data:{url:$url,iterator:$iterator,filter:$filter,limit:$limit,sortBy:$sortBy}){id url title description isNsfw children{iterator items{id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt isPaid albumContent{mediaSources{url width height isOptimized}} mediaSources{url width height isOptimized}}}}}`,
  SearchSubreddits:`query SearchSubreddits($data:SearchSubredditsInput!){searchSubreddits(data:$data){id url title description item_count is_nsfw}}`,
  GetCategories:`query GetCategories($is_nsfw:Boolean!){categories(data:{is_nsfw:$is_nsfw}){title}}`,
  GetCategory:`query GetCategory($url:String!){getCategory(data:{url:$url}){id url title isNsfw}}`,
  GetCategorySubreddits:`query GetCategorySubreddits($categoryId:Int!){getCategorySubreddits(data:{categoryId:$categoryId}){subreddits{subredditUrl}}}`,
  GetUserCollections:`query GetUserCollections{getUserCollections{id url title isNsfw}}`,
  GetLoggedInUser:`query GetLoggedInUser{getLoggedInUser{id username email}}`
};

async function gql(operation, variables={}) {
  const res = await nativePost('https://api.scrolller.com/admin', JSON.stringify({query:QUERIES[operation],variables}), {'Content-Type':'application/json'});
  const json = JSON.parse(res.body || '{}');
  if(json.errors?.length) throw new Error(json.errors[0].message || 'Scrolller API error');
  return json.data || {};
}

function bestSource(sources=[]){ return sources.filter(s=>s?.url).sort((a,b)=>((b.width||0)*(b.height||0))-((a.width||0)*(a.height||0)))[0] || null; }
function normalizeScrolller(item){
  if(!item || item.isPaid) return null;
  let album=[];
  if(Array.isArray(item.albumContent)) album=item.albumContent.map(x=>bestSource(x.mediaSources||[])?.url).filter(Boolean);
  else if(item.albumContent && Array.isArray(item.albumContent.mediaSources)){ const u=bestSource(item.albumContent.mediaSources)?.url; if(u) album=[u]; }
  const sources=item.mediaSources||[];
  const video=bestSource(sources.filter(s=>/\.(mp4|webm)(\?|$)/i.test(s.url||'')));
  const image=bestSource(sources.filter(s=>!/\.(mp4|webm)(\?|$)/i.test(s.url||'')));
  const url=album[0]||video?.url||image?.url;
  if(!url) return null;
  const mediaType=album.length>1?'ALBUM':video?'VIDEO':/\.gif(\?|$)/i.test(url)?'GIF':'IMAGE';
  return {id:`scrolller:${item.id}`,title:item.title||'Untitled',collection:item.subredditTitle||item.subredditUrl||'Scrolller',collectionUrl:item.subredditUrl||'',url,poster:image?.url||album[0]||'',album,mediaType,nsfw:!!item.isNsfw,created:item.createdAt?Date.parse(item.createdAt)||0:0,sourceUrl:item.redditPath?`https://reddit.com${item.redditPath}`:''};
}

function categorySlug(title){ return String(title||'').trim().toLowerCase().replace(/&/g,'and').replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,''); }
async function scrolllerCollections(query='', pages=6){
  const all=[]; const seen=new Set();
  for(let page=1;page<=pages;page++){
    const d=await gql('SearchSubreddits',{data:{query,limit:100,pageIndex:page,isNsfw:state.nsfw!=='SFW'}});
    const items=d.searchSubreddits||[];
    for(const c of items){ const key=String(c.id||c.url); if(!seen.has(key)){seen.add(key);all.push(c);} }
    if(items.length<100) break;
  }
  return all;
}
async function scrolllerSubreddit(url,limit=80){
  const d=await gql('SubredditQuery',{url,iterator:null,sortBy:state.sort==='RANDOM'?'RANDOM':state.sort,filter:null,limit});
  return (d.getSubreddit?.children?.items||[]).map(normalizeScrolller).filter(Boolean);
}
async function categoryCollections(title){
  const c=await gql('GetCategory',{url:categorySlug(title)});
  if(!c.getCategory?.id) throw new Error('Category lookup failed');
  const s=await gql('GetCategorySubreddits',{categoryId:c.getCategory.id});
  return (s.getCategorySubreddits?.subreddits||[]).map(x=>x.subredditUrl).filter(Boolean);
}
async function pooledSubreddits(urls, perCollection=24){
  const out=[];
  for(let i=0;i<urls.length;i+=8){
    const batch=await Promise.allSettled(urls.slice(i,i+8).map(u=>scrolllerSubreddit(u,perCollection)));
    out.push(...batch.flatMap(x=>x.status==='fulfilled'?x.value:[]));
  }
  return out;
}
async function searchScrolllerPosts(query){
  let urls=[];
  if(state.category!=='ALL') urls=await categoryCollections(state.category);
  else {
    const groups=await scrolllerCollections(query||'popular',4);
    urls=groups.map(g=>g.url).filter(Boolean);
    if(!urls.length&&!query) urls=['funny'];
  }
  const posts=await pooledSubreddits(urls.slice(0,40),24);
  if(!query || state.category==='ALL') return posts;
  const q=query.toLowerCase();
  return posts.filter(p=>(p.title+' '+p.collection).toLowerCase().includes(q));
}

function filterPosts(posts){
  const seen=new Set();
  let out=posts.filter(p=>{
    if(!p?.url) return false;
    if(state.media!=='ALL' && p.mediaType!==state.media) return false;
    if(state.nsfw==='SFW' && p.nsfw) return false;
    if(state.nsfw==='NSFW' && !p.nsfw) return false;
    const key=p.url.replace(/\?.*$/,'').toLowerCase();
    if(seen.has(key)) return false;
    seen.add(key); return true;
  });
  if(state.sort==='NEW') out.sort((a,b)=>b.created-a.created);
  if(state.sort==='RANDOM') out.sort(()=>Math.random()-.5);
  return out;
}

async function runSearch(query=state.query,push=true){
  if(push) pushHistory();
  closeSheets();
  state.query=query.trim(); localStorage.setItem('query',state.query);
  state.currentLabel=state.query?`Search: ${state.query}`:(state.category!=='ALL'?state.category:'Feed');
  $('titleBtn').textContent=state.currentLabel;
  setBusy(true,'Loading posts…'); $('empty').classList.add('hidden');
  try{ state.rawPosts=await searchScrolllerPosts(state.query); state.posts=filterPosts(state.rawPosts); state.activeIndex=0; renderFeed(); }
  catch(e){ state.posts=[]; renderFeed(); toast(e.message); }
  finally{ setBusy(false); }
}

function escapeHtml(s=''){ return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
function mediaMarkup(p){
  if(p.mediaType==='VIDEO'||p.mediaType==='GIF') return `<div class="media-frame"><video class="post-media" src="${escapeHtml(p.url)}" ${p.poster?`poster="${escapeHtml(p.poster)}"`:''} loop playsinline muted preload="metadata"></video></div>`;
  return `<div class="media-frame"><img class="post-media" src="${escapeHtml(p.url)}" alt=""></div>`;
}
function renderFeed(){
  const feed=$('feed'); if(videoObserver) videoObserver.disconnect(); feed.innerHTML='';
  $('empty').classList.toggle('hidden',!!state.posts.length);
  const frag=document.createDocumentFragment();
  state.posts.forEach((p,i)=>{
    const el=document.createElement('section'); el.className='post'; el.dataset.index=String(i);
    el.innerHTML=`${mediaMarkup(p)}<div class="post-gradient"></div>${p.album?.length>1?`<div class="album-count">1/${p.album.length}</div>`:''}<div class="post-info"><div class="post-title">${escapeHtml(p.title)}</div><div class="post-meta"><strong>${escapeHtml(p.collection)}</strong> · ${escapeHtml(p.mediaType)}</div></div><div class="actions"><button class="action similar"><b>≈</b>Similar</button>${p.album?.length>1?'<button class="action next-album"><b>›</b>Album</button>':''}${p.mediaType==='VIDEO'||p.mediaType==='GIF'?'<button class="action mute"><b>◖</b>Sound</button>':''}</div>`;
    el.querySelector('.similar').onclick=()=>similarFrom(p);
    const mute=el.querySelector('.mute'); if(mute) mute.onclick=()=>{const v=el.querySelector('video');if(v){v.muted=!v.muted;mute.innerHTML=`<b>${v.muted?'◖':'◗'}</b>${v.muted?'Sound':'Mute'}`;}};
    const ab=el.querySelector('.next-album'); if(ab){let ai=0;ab.onclick=()=>{ai=(ai+1)%p.album.length;el.querySelector('.post-media').src=p.album[ai];el.querySelector('.album-count').textContent=`${ai+1}/${p.album.length}`;};}
    frag.appendChild(el);
  });
  feed.appendChild(frag); setupVideos();
  setTimeout(()=>scrollToIndex(Math.min(state.activeIndex,Math.max(0,state.posts.length-1)),false),30);
}
function setupVideos(){
  videoObserver=new IntersectionObserver(entries=>entries.forEach(e=>{const v=e.target;if(e.isIntersecting&&e.intersectionRatio>.7)v.play().catch(()=>{});else v.pause();}),{threshold:[.2,.7,.95]});
  document.querySelectorAll('video.post-media').forEach(v=>videoObserver.observe(v));
}
function scrollToIndex(i,smooth=true){ const c=$('feed').children[i]; if(c)c.scrollIntoView({behavior:smooth?'smooth':'auto',block:'start'}); }
$('feed').addEventListener('scroll',()=>{state.activeIndex=currentVisibleIndex();},{passive:true});
async function similarFrom(post){
  const words=(post.title||'').toLowerCase().replace(/[^a-z0-9 ]/g,' ').split(/\s+/).filter(w=>w.length>3&&!['this','that','with','from','have','your','just','when','what'].includes(w)).slice(0,6);
  await runSearch(words.join(' ')||post.collection,true);
}

async function loadCategories(){
  try{const d=await gql('GetCategories',{is_nsfw:state.nsfw!=='SFW'});const titles=(d.categories||[]).map(x=>x.title).filter(Boolean).sort();const s=$('categorySelect');s.innerHTML='<option value="ALL">All categories</option>';titles.forEach(t=>{const o=document.createElement('option');o.value=t;o.textContent=t;s.appendChild(o);});s.value=state.category;}catch(e){console.warn(e);}
}
async function showCollectionResults(query,target='collectionResults'){
  const box=$(target); box.innerHTML='<div class="result">Searching…</div>';
  try{const items=await scrolllerCollections(query,8);box.innerHTML='';items.forEach(c=>{const el=document.createElement('div');el.className='result';el.innerHTML=`<div class="result-title">${escapeHtml(c.title||c.url)}</div><div class="result-sub">${escapeHtml(c.description||'')}${c.item_count?` · ${c.item_count} items`:''}</div>`;el.onclick=()=>openCollection(c);box.appendChild(el);});if(!items.length)box.innerHTML='<div class="result">No collections found.</div>';}
  catch(e){box.innerHTML=`<div class="result">${escapeHtml(e.message)}</div>`;}
}
async function openCollection(c){
  pushHistory(); closeSheets(); setBusy(true,'Opening collection…');
  try{state.query='';state.category='ALL';state.currentLabel=c.title||c.url;$('titleBtn').textContent=state.currentLabel;state.rawPosts=await scrolllerSubreddit(c.url,120);state.posts=filterPosts(state.rawPosts);state.activeIndex=0;renderFeed();}
  catch(e){toast(e.message);}finally{setBusy(false);}
}
async function showMyCollections(){
  const box=$('collectionResults'); box.innerHTML='<div class="result">Loading…</div>';
  try{const d=await gql('GetUserCollections',{});const items=d.getUserCollections||[];box.innerHTML='';items.forEach(c=>{const el=document.createElement('div');el.className='result';el.innerHTML=`<div class="result-title">${escapeHtml(c.title||c.url)}</div><div class="result-sub">Your Scrolller collection</div>`;el.onclick=()=>openCollection(c);box.appendChild(el);});if(!items.length)box.innerHTML='<div class="result">No account collections returned.</div>';}
  catch(e){box.innerHTML='<div class="result">Sign in first, then return with Android Back.</div>';}
}
async function checkAccount(){
  const status=$('accountStatus');
  try{const d=await gql('GetLoggedInUser',{});const u=d.getLoggedInUser;if(u?.username){state.account=u;status.textContent=`Signed in as ${u.username}`;$('accountBtn').classList.add('signed-in');$('loginBtn').textContent='Open Scrolller account';return;}}
  catch(e){}
  state.account=null;status.textContent='Not signed in';$('accountBtn').classList.remove('signed-in');$('loginBtn').textContent='Sign in to Scrolller';
}
function syncControls(){
  document.querySelectorAll('#scopeSwitch button').forEach(b=>b.classList.toggle('active',b.dataset.scope===state.scope));
  $('sortSelect').value=state.sort;$('mediaSelect').value=state.media;$('nsfwSelect').value=state.nsfw;
  if([...$('categorySelect').options].some(o=>o.value===state.category))$('categorySelect').value=state.category;
}

document.querySelectorAll('[data-close]').forEach(b=>b.onclick=closeSheets);
document.querySelectorAll('#scopeSwitch button').forEach(b=>b.onclick=()=>{state.scope=b.dataset.scope;syncControls();});
$('backBtn').onclick=()=>window.ScrolllerNativeBack();
$('titleBtn').onclick=()=>scrollToIndex(0,true);
$('searchBtn').onclick=()=>{openSheet('searchSheet');setTimeout(()=>$('searchInput').focus(),50);};
$('filterBtn').onclick=()=>openSheet('filterSheet');
$('collectionsBtn').onclick=()=>openSheet('collectionsSheet');
$('accountBtn').onclick=async()=>{openSheet('accountSheet');await checkAccount();};
$('feedBtn').onclick=()=>scrollToIndex(0,true);
$('randomBtn').onclick=async()=>{state.sort='RANDOM';localStorage.setItem('sort','RANDOM');syncControls();await runSearch('',true);};
$('searchForm').onsubmit=async e=>{e.preventDefault();const q=$('searchInput').value.trim();if(state.scope==='COLLECTIONS')await showCollectionResults(q,'searchResults');else await runSearch(q,true);};
$('collectionSearchBtn').onclick=()=>showCollectionResults($('collectionQuery').value.trim());
$('myCollectionsBtn').onclick=showMyCollections;
$('applyFilters').onclick=async()=>{state.sort=$('sortSelect').value;state.media=$('mediaSelect').value;state.category=$('categorySelect').value;state.nsfw=$('nsfwSelect').value;['sort','media','category','nsfw'].forEach(k=>localStorage.setItem(k,state[k]));closeSheets();syncControls();await loadCategories();await runSearch(state.query,true);};
$('loginBtn').onclick=()=>{try{localStorage.setItem('resumeState',JSON.stringify({query:state.query,sort:state.sort,media:state.media,category:state.category,nsfw:state.nsfw}));if(window.NativeAuth)NativeAuth.openLogin();else toast('Sign-in is available in the Android app.');}catch(e){toast(e.message);}};

(async function init(){
  try{const resume=JSON.parse(localStorage.getItem('resumeState')||'null');if(resume){Object.assign(state,resume);localStorage.removeItem('resumeState');}}catch(e){}
  syncControls(); await loadCategories(); await checkAccount(); await runSearch(state.query,false);
})();