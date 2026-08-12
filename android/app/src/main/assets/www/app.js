const $ = (id) => document.getElementById(id);

const state = {
  source: localStorage.getItem('source') || 'ALL',
  scope: 'POSTS',
  sort: localStorage.getItem('sort') || 'HOT',
  media: localStorage.getItem('media') || 'ALL',
  category: localStorage.getItem('category') || 'ALL',
  nsfw: localStorage.getItem('nsfw') || 'ALL',
  query: localStorage.getItem('query') || '',
  posts: [], rawPosts: [], history: [], redgifsToken: null,
  activeIndex: 0, currentLabel: 'Feed'
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

function nativeGet(url, headers = {}) {
  if (!window.NativeMedia) return fetch(url, { headers }).then(async r => ({ body: await r.text(), status: r.status }));
  return new Promise((resolve, reject) => {
    const id = `g${Date.now()}_${++requestSeq}`;
    pending.set(id, { resolve, reject });
    NativeMedia.get(id, url, JSON.stringify(headers));
  });
}

function nativePost(url, body, headers = {}) {
  if (!window.NativeMedia) return fetch(url, { method: 'POST', headers, body }).then(async r => ({ body: await r.text(), status: r.status }));
  return new Promise((resolve, reject) => {
    const id = `p${Date.now()}_${++requestSeq}`;
    pending.set(id, { resolve, reject });
    NativeMedia.postJson(id, url, body, JSON.stringify(headers));
  });
}

function toast(message) {
  const el = $('toast'); el.textContent = message; el.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => el.classList.add('hidden'), 2500);
}
function setBusy(on, text = 'Loading…') { $('busy').textContent = text; $('busy').classList.toggle('hidden', !on); }
function openSheet(id) { $(id).classList.remove('hidden'); }
function closeSheets() { document.querySelectorAll('.sheet').forEach(s => s.classList.add('hidden')); }

function snapshot() {
  return { source:state.source, scope:state.scope, sort:state.sort, media:state.media, category:state.category,
    nsfw:state.nsfw, query:state.query, posts:state.posts, rawPosts:state.rawPosts,
    activeIndex:currentVisibleIndex(), currentLabel:state.currentLabel };
}
function pushHistory() { const s=snapshot(); if(s.posts.length) state.history.push(s); if(state.history.length>12) state.history.shift(); }
function restore(s) { Object.assign(state,s); syncControls(); renderFeed(); setTimeout(()=>scrollToIndex(s.activeIndex||0,false),40); }
window.ScrolllerNativeBack = function() {
  const open=[...document.querySelectorAll('.sheet')].find(s=>!s.classList.contains('hidden'));
  if(open){closeSheets();return true;} if(state.history.length){restore(state.history.pop());return true;} return false;
};

const QUERIES = {
  SubredditQuery:`query SubredditQuery($url:String!,$iterator:String,$sortBy:GallerySortBy,$filter:GalleryFilter,$limit:Int!){getSubreddit(data:{url:$url,iterator:$iterator,filter:$filter,limit:$limit,sortBy:$sortBy}){id url title description isNsfw children{iterator items{id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt isPaid albumContent{mediaSources{url width height isOptimized}} mediaSources{url width height isOptimized}}}}}`,
  SearchSubreddits:`query SearchSubreddits($data:SearchSubredditsInput!){searchSubreddits(data:$data){id url title description item_count is_nsfw}}`,
  GetCategories:`query GetCategories($is_nsfw:Boolean!){categories(data:{is_nsfw:$is_nsfw}){title}}`,
  GetCategory:`query GetCategory($url:String!){getCategory(data:{url:$url}){id url title isNsfw}}`,
  GetCategorySubreddits:`query GetCategorySubreddits($categoryId:Int!){getCategorySubreddits(data:{categoryId:$categoryId}){subreddits{subredditUrl}}}`,
  GetUserCollections:`query GetUserCollections{getUserCollections{id url title isNsfw}}`
};

async function gql(operation, variables) {
  const res=await nativePost('https://api.scrolller.com/admin',JSON.stringify({query:QUERIES[operation],variables}),{'Content-Type':'application/json'});
  const json=JSON.parse(res.body||'{}'); if(json.errors?.length) throw new Error(json.errors[0].message||'Scrolller GraphQL error'); return json.data||{};
}
function bestSource(sources=[]){const a=sources.filter(s=>s&&s.url).sort((x,y)=>((y.width||0)*(y.height||0))-((x.width||0)*(x.height||0)));return a[0]||null;}
function normalizeScrolller(item){
  if(!item||item.isPaid)return null; let album=[];
  if(Array.isArray(item.albumContent)) album=item.albumContent.map(x=>bestSource(x.mediaSources||[])?.url).filter(Boolean);
  else if(item.albumContent&&Array.isArray(item.albumContent.mediaSources)){const u=bestSource(item.albumContent.mediaSources)?.url;if(u)album=[u];}
  const sources=item.mediaSources||[]; const video=bestSource(sources.filter(s=>/\.(mp4|webm)(\?|$)/i.test(s.url||''))); const image=bestSource(sources.filter(s=>!/\.(mp4|webm)(\?|$)/i.test(s.url||'')));
  const url=album[0]||video?.url||image?.url;if(!url)return null; const mediaType=album.length>1?'ALBUM':video?'VIDEO':/\.gif(\?|$)/i.test(url)?'GIF':'IMAGE';
  return {id:`scrolller:${item.id}`,source:'SCROLLLER',title:item.title||'Untitled',collection:item.subredditTitle||item.subredditUrl||'Scrolller',collectionUrl:item.subredditUrl||'',url,poster:image?.url||album[0]||'',album,mediaType,nsfw:!!item.isNsfw,created:item.createdAt?Date.parse(item.createdAt)||0:0,score:0,sourceUrl:item.redditPath?`https://reddit.com${item.redditPath}`:''};
}
function normalizeReddit(d){
  const data=d?.data||d;if(!data||data.stickied)return null;let mediaType='IMAGE',url='',poster='',album=[]; const rv=data.secure_media?.reddit_video||data.media?.reddit_video||data.preview?.reddit_video_preview;
  if(rv?.fallback_url){mediaType='VIDEO';url=rv.fallback_url;poster=data.thumbnail&&/^https?:/.test(data.thumbnail)?data.thumbnail:'';}
  else if(data.is_gallery&&data.media_metadata){album=Object.values(data.media_metadata).map(m=>{const s=m.s?m.s.u||m.s.gif:null;return s?s.replaceAll('&amp;','&'):null;}).filter(Boolean);if(album.length){mediaType='ALBUM';url=album[0];poster=url;}}
  else {const dest=(data.url_overridden_by_dest||data.url||'').replaceAll('&amp;','&'); if(/redgifs\.com\/watch\//i.test(dest)) return {_redgifsWatch:dest,reddit:data}; if(/\.(mp4|webm)(\?|$)/i.test(dest))mediaType='VIDEO';else if(/\.gif(\?|$)/i.test(dest))mediaType='GIF';if(/^https?:/.test(dest))url=dest;const preview=data.preview?.images?.[0]?.source?.url?.replaceAll('&amp;','&');if(!url&&preview)url=preview;poster=preview||(data.thumbnail&&/^https?:/.test(data.thumbnail)?data.thumbnail:'');}
  if(!url)return null; return {id:`reddit:${data.name||data.id}`,source:'REDDIT',title:data.title||'Reddit post',collection:data.subreddit_name_prefixed||`r/${data.subreddit||''}`,collectionUrl:data.subreddit||'',url,poster,album,mediaType,nsfw:!!data.over_18,created:(data.created_utc||0)*1000,score:data.score||0,sourceUrl:`https://www.reddit.com${data.permalink||''}`};
}
async function redgifsToken(){if(state.redgifsToken)return state.redgifsToken;const r=await nativeGet('https://api.redgifs.com/v2/auth/temporary');const j=JSON.parse(r.body||'{}');state.redgifsToken=j.token||j.auth?.token||'';if(!state.redgifsToken)throw new Error('RedGIFs temporary token unavailable');return state.redgifsToken;}
function normalizeRedgif(g){if(!g)return null;const urls=g.urls||{};const url=urls.hd||urls.sd||urls.web_url||urls.webUrl||'';if(!url)return null;return{id:`redgifs:${g.id||url}`,source:'REDGIFS',title:g.description||g.title||(g.tags||[]).slice(0,4).join(' · ')||'RedGIFs',collection:g.userName||g.username||'RedGIFs',collectionUrl:g.userName||'',url,poster:urls.poster||urls.thumbnail||'',album:[],mediaType:'VIDEO',nsfw:true,created:g.createDate?g.createDate*1000:0,score:g.views||0,sourceUrl:g.id?`https://www.redgifs.com/watch/${g.id}`:''};}
async function resolveRedgifsWatch(watchUrl){const id=(watchUrl.match(/\/watch\/([^/?#]+)/i)||[])[1];if(!id)return null;const token=await redgifsToken();const r=await nativeGet(`https://api.redgifs.com/v2/gifs/${encodeURIComponent(id)}`,{Authorization:`Bearer ${token}`});const j=JSON.parse(r.body||'{}');return normalizeRedgif(j.gif||j);}

async function scrolllerCollections(query){const d=await gql('SearchSubreddits',{data:{query,limit:80,pageIndex:1,isNsfw:state.nsfw!=='SFW'}});return d.searchSubreddits||[];}
async function scrolllerSubreddit(url,limit=60){const d=await gql('SubredditQuery',{url,iterator:null,sortBy:state.sort==='RANDOM'?'RANDOM':state.sort,filter:null,limit});return(d.getSubreddit?.children?.items||[]).map(normalizeScrolller).filter(Boolean);}
function categorySlug(title){return String(title||'').trim().toLowerCase().replace(/&/g,'and').replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');}
async function scrolllerCategoryPosts(title){const c=await gql('GetCategory',{url:categorySlug(title)});if(!c.getCategory?.id)throw new Error('Category lookup failed');const s=await gql('GetCategorySubreddits',{categoryId:c.getCategory.id});const urls=(s.getCategorySubreddits?.subreddits||[]).map(x=>x.subredditUrl).filter(Boolean).slice(0,24);const batches=await Promise.allSettled(urls.map(u=>scrolllerSubreddit(u,18)));return batches.flatMap(x=>x.status==='fulfilled'?x.value:[]);}
async function searchScrolllerPosts(query){if(state.category!=='ALL')return scrolllerCategoryPosts(state.category);const groups=await scrolllerCollections(query||'popular');const urls=groups.map(g=>g.url).filter(Boolean).slice(0,18);if(!urls.length&&!query)urls.push('funny');const b=await Promise.allSettled(urls.map(u=>scrolllerSubreddit(u,18)));return b.flatMap(x=>x.status==='fulfilled'?x.value:[]);}
async function searchReddit(query){if(!query)query='sort:hot';const sort=state.sort==='NEW'?'new':state.sort==='TOP'?'top':'relevance';const p=new URLSearchParams({q:query,limit:'100',sort,raw_json:'1',type:'link',include_over_18:state.nsfw==='SFW'?'0':'1'});const r=await nativeGet(`https://www.reddit.com/search.json?${p.toString()}`);const j=JSON.parse(r.body||'{}');const out=[];for(const child of(j.data?.children||[])){let n=normalizeReddit(child);if(n?._redgifsWatch){try{n=await resolveRedgifsWatch(n._redgifsWatch);}catch{n=null;}}if(n)out.push(n);}return out;}
async function searchRedgifs(query){if(!query)query='trending';const token=await redgifsToken();const order=state.sort==='NEW'?'latest':'trending';const r=await nativeGet(`https://api.redgifs.com/v2/gifs/search?search_text=${encodeURIComponent(query)}&order=${order}&count=80&page=1`,{Authorization:`Bearer ${token}`});const j=JSON.parse(r.body||'{}');return(j.gifs||j.items||[]).map(normalizeRedgif).filter(Boolean);}

function filterPosts(posts){const seen=new Set();let out=posts.filter(p=>{if(!p?.url)return false;if(state.media!=='ALL'&&p.mediaType!==state.media)return false;if(state.nsfw==='SFW'&&p.nsfw)return false;if(state.nsfw==='NSFW'&&!p.nsfw)return false;const k=(p.url||'').replace(/\?.*$/,'').toLowerCase();if(seen.has(k))return false;seen.add(k);return true;});if(state.sort==='NEW')out.sort((a,b)=>b.created-a.created);if(state.sort==='TOP')out.sort((a,b)=>(b.score||0)-(a.score||0));if(state.sort==='RANDOM')out.sort(()=>Math.random()-.5);return out;}
async function runSearch(query=state.query,push=true){if(push)pushHistory();closeSheets();state.query=query.trim();localStorage.setItem('query',state.query);setBusy(true,'Searching media…');$('empty').classList.add('hidden');const jobs=[],errors=[];if(state.source==='ALL'||state.source==='SCROLLLER')jobs.push(searchScrolllerPosts(state.query).catch(e=>{errors.push(`Scrolller: ${e.message}`);return[];}));if(state.source==='ALL'||state.source==='REDDIT')jobs.push(searchReddit(state.query).catch(e=>{errors.push(`Reddit: ${e.message}`);return[];}));if(state.source==='ALL'||state.source==='REDGIFS')jobs.push(searchRedgifs(state.query).catch(e=>{errors.push(`RedGIFs: ${e.message}`);return[];}));try{const groups=await Promise.all(jobs);state.rawPosts=groups.flat();state.posts=filterPosts(state.rawPosts);renderFeed(errors);}finally{setBusy(false);}}

function escapeHtml(s=''){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function mediaMarkup(p){if(p.mediaType==='VIDEO'||p.mediaType==='GIF')return `<video class="post-media" src="${escapeHtml(p.url)}" ${p.poster?`poster="${escapeHtml(p.poster)}"`:''} loop playsinline muted preload="metadata"></video>`;return `<img class="post-media" src="${escapeHtml(p.url)}" alt="">`;}
function renderFeed(errors=[]){const feed=$('feed');if(videoObserver)videoObserver.disconnect();feed.innerHTML='';$('empty').classList.toggle('hidden',!!state.posts.length);const frag=document.createDocumentFragment();state.posts.forEach((p,i)=>{const el=document.createElement('section');el.className=`post ${p.mediaType==='ALBUM'?'album':''}`;el.dataset.index=String(i);el.innerHTML=`${mediaMarkup(p)}<div class="post-gradient"></div>${p.album?.length>1?`<div class="album-count">1/${p.album.length}</div>`:''}<div class="post-info"><div class="post-title">${escapeHtml(p.title)}</div><div class="post-meta"><span class="badge">${escapeHtml(p.source)}</span><span class="badge">${escapeHtml(p.mediaType)}</span>${escapeHtml(p.collection||'')}</div></div><div class="actions"><button class="action similar"><b>≈</b>Similar</button>${p.album?.length>1?'<button class="action next-album"><b>›</b>Album</button>':''}<button class="action mute"><b>◖</b>Sound</button></div>`;el.querySelector('.similar').onclick=()=>similarFrom(p);const mute=el.querySelector('.mute');mute.onclick=()=>{const v=el.querySelector('video');if(v){v.muted=!v.muted;mute.lastChild.textContent=v.muted?'Sound':'Mute';}};const ab=el.querySelector('.next-album');if(ab){let ai=0;ab.onclick=()=>{ai=(ai+1)%p.album.length;el.querySelector('.post-media').src=p.album[ai];el.querySelector('.album-count').textContent=`${ai+1}/${p.album.length}`;};}frag.appendChild(el);});feed.appendChild(frag);setupVideos();if(errors.length)toast(errors.join(' • '));setTimeout(()=>scrollToIndex(Math.min(state.activeIndex,state.posts.length-1),false),30);}
function setupVideos(){videoObserver=new IntersectionObserver(entries=>entries.forEach(e=>{const v=e.target;if(e.isIntersecting&&e.intersectionRatio>.72)v.play().catch(()=>{});else v.pause();}),{threshold:[.2,.72,.95]});document.querySelectorAll('video.post-media').forEach(v=>videoObserver.observe(v));}
function currentVisibleIndex(){const f=$('feed');if(!f||!state.posts.length)return 0;return Math.max(0,Math.min(state.posts.length-1,Math.round(f.scrollTop/Math.max(1,f.clientHeight))));}
function scrollToIndex(i,smooth=true){const c=$('feed').children[i];if(c)c.scrollIntoView({behavior:smooth?'smooth':'auto',block:'start'});}
$('feed').addEventListener('scroll',()=>{state.activeIndex=currentVisibleIndex();},{passive:true});
async function similarFrom(post){const words=(post.title||'').toLowerCase().replace(/[^a-z0-9 ]/g,' ').split(/\s+/).filter(w=>w.length>3&&!['this','that','with','from','have','your','just','when','what'].includes(w)).slice(0,6);await runSearch(words.join(' ')||post.collection.replace(/^r\//,''),true);}

async function loadCategories(){try{const d=await gql('GetCategories',{is_nsfw:state.nsfw!=='SFW'});const titles=(d.categories||[]).map(x=>x.title).filter(Boolean).sort();const s=$('categorySelect');s.innerHTML='<option value="ALL">All categories</option>';titles.forEach(t=>{const o=document.createElement('option');o.value=t;o.textContent=t;s.appendChild(o);});s.value=state.category;}catch(e){console.warn('Category load failed',e);}}
async function showCollectionResults(query,target='collectionResults'){const box=$(target);box.innerHTML='Loading…';try{const items=await scrolllerCollections(query||'');box.innerHTML='';items.forEach(c=>{const el=document.createElement('div');el.className='result';el.innerHTML=`<div class="result-title">${escapeHtml(c.title||c.url)}</div><div class="result-sub">${escapeHtml(c.description||'')} ${c.item_count?`· ${c.item_count} items`:''}</div>`;el.onclick=async()=>{pushHistory();closeSheets();state.query='';state.source='SCROLLLER';syncControls();setBusy(true,'Opening collection…');try{state.rawPosts=await scrolllerSubreddit(c.url,100);state.posts=filterPosts(state.rawPosts);renderFeed();}catch(e){toast(e.message)}finally{setBusy(false);}};box.appendChild(el);});if(!items.length)box.innerHTML='<div class="result">No collections found.</div>';}catch(e){box.innerHTML=`<div class="result">Collection search failed: ${escapeHtml(e.message)}</div>`;}}
async function showMyCollections(){const box=$('collectionResults');box.innerHTML='Loading account collections…';try{const d=await gql('GetUserCollections',{});const items=d.getUserCollections||[];box.innerHTML='';items.forEach(c=>{const el=document.createElement('div');el.className='result';el.innerHTML=`<div class="result-title">${escapeHtml(c.title||c.url)}</div><div class="result-sub">Scrolller collection</div>`;el.onclick=async()=>{pushHistory();closeSheets();state.source='SCROLLLER';syncControls();setBusy(true);try{state.rawPosts=await scrolllerSubreddit(c.url,100);state.posts=filterPosts(state.rawPosts);renderFeed();}catch(e){toast(e.message)}finally{setBusy(false);}};box.appendChild(el);});if(!items.length)box.innerHTML='<div class="result">No signed-in collections returned. Sign in first.</div>';}catch(e){box.innerHTML=`<div class="result">Sign-in/session required: ${escapeHtml(e.message)}</div>`;}}
function syncControls(){document.querySelectorAll('#sourceStrip button').forEach(b=>b.classList.toggle('active',b.dataset.source===state.source));document.querySelectorAll('#scopeSwitch button').forEach(b=>b.classList.toggle('active',b.dataset.scope===state.scope));$('sortSelect').value=state.sort;$('mediaSelect').value=state.media;$('nsfwSelect').value=state.nsfw;if([...$('categorySelect').options].some(o=>o.value===state.category))$('categorySelect').value=state.category;}

document.querySelectorAll('#sourceStrip button').forEach(b=>b.onclick=()=>{state.source=b.dataset.source;localStorage.setItem('source',state.source);syncControls();runSearch(state.query,true);});
document.querySelectorAll('#scopeSwitch button').forEach(b=>b.onclick=()=>{state.scope=b.dataset.scope;syncControls();});
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=closeSheets);
$('filterBtn').onclick=()=>openSheet('filterSheet');$('searchBtn').onclick=()=>{openSheet('searchSheet');setTimeout(()=>$('searchInput').focus(),50);};$('collectionsBtn').onclick=()=>openSheet('collectionsSheet');$('feedBtn').onclick=()=>scrollToIndex(0,true);$('backBtn').onclick=()=>window.ScrolllerNativeBack();
$('searchForm').onsubmit=async e=>{e.preventDefault();const q=$('searchInput').value.trim();if(state.scope==='COLLECTIONS')await showCollectionResults(q,'searchResults');else await runSearch(q,true);};
$('collectionSearchBtn').onclick=()=>showCollectionResults($('collectionQuery').value.trim());$('myCollectionsBtn').onclick=showMyCollections;
$('applyFilters').onclick=async()=>{const categoryChanged=state.category!==$('categorySelect').value;state.sort=$('sortSelect').value;state.media=$('mediaSelect').value;state.category=$('categorySelect').value;state.nsfw=$('nsfwSelect').value;['sort','media','category','nsfw'].forEach(k=>localStorage.setItem(k,state[k]));closeSheets();syncControls();if(categoryChanged||state.source==='SCROLLLER'||state.source==='ALL')await runSearch(state.query,true);else{state.posts=filterPosts(state.rawPosts);renderFeed();}};
$('loginBtn').onclick=()=>{try{localStorage.setItem('resumeState',JSON.stringify({source:state.source,query:state.query,sort:state.sort,media:state.media,category:state.category,nsfw:state.nsfw}));if(window.NativeAuth)NativeAuth.openLogin();else toast('Login bridge is only available in the Android app.');}catch(e){toast(e.message);}};

(async function init(){try{const resume=JSON.parse(localStorage.getItem('resumeState')||'null');if(resume){Object.assign(state,resume);localStorage.removeItem('resumeState');}}catch{}syncControls();await loadCategories();await runSearch(state.query,false);})();
