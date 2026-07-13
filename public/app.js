/**
 * Scrolller Pro - Main Application Logic
 */

// App State
const state = {
    currentSubreddit: 'funny',
    posts: [],
    favorites: {
        subreddits: [],
        posts: []
    },
    filter: 'ALL', // ALL, PICTURE, VIDEO, ALBUM
    sortBy: 'HOT', // HOT, NEW, OLD, TOP, RANDOM
    nsfwFilter: 'SFW', // SFW, NSFW, ALL
    iterator: null,
    subredditId: null,
    isNsfwSubreddit: false,
    isCollection: false,
    loading: false,
    hasMore: true,
    currentViewerIndex: 0,
    immersiveMode: false,
    gridCols: 2,
    token: localStorage.getItem('scrolller_token') || '',
    userProfile: null,
    userCollections: []
};

// SVG icons for direct JS rendering
const icons = {
    play: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>',
    pause: '<svg viewBox="0 0 24 24" width="20" height="20"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z" fill="currentColor"/></svg>',
    volumeUp: '<svg viewBox="0 0 24 24" width="18" height="18"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z" fill="currentColor"/></svg>',
    volumeMute: '<svg viewBox="0 0 24 24" width="18" height="18"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.21.05-.42.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z" fill="currentColor"/></svg>'
};

// Initialize Application
document.addEventListener('DOMContentLoaded', () => {
    loadSettings();
    initEventListeners();
    loadCategoriesFilter();
    loadSubreddit(state.currentSubreddit);
    setupIntersectionObserver();
    
    // Check local storage for initial column count preference
    if (localStorage.getItem('gridCols')) {
        updateColumns(parseInt(localStorage.getItem('gridCols')));
    } else if (window.innerWidth <= 768) {
        updateColumns(1);
    }
});

// Load variables from localStorage
function loadSettings() {
    state.nsfwFilter = localStorage.getItem('nsfwFilter') || 'SFW';
    document.getElementById('nsfw-filter-select').value = state.nsfwFilter;
    
    // Load favorites
    const savedFavorites = localStorage.getItem('scrolller_favorites');
    if (savedFavorites) {
        state.favorites = JSON.parse(savedFavorites);
    }
    renderFavorites();

    // Check for Scrolller session token
    state.token = localStorage.getItem('scrolller_token') || '';
    if (state.token) {
        syncUserProfile(state.token);
    }
}

// Save variables to localStorage
function saveSettings() {
    localStorage.setItem('nsfwFilter', state.nsfwFilter);
    localStorage.setItem('scrolller_favorites', JSON.stringify(state.favorites));
}

// Setup intersection observer for autoplaying video
let videoObserver;
function setupIntersectionObserver() {
    const options = {
        root: null,
        rootMargin: '0px',
        threshold: 0.6 // Video must be 60% in view
    };

    videoObserver = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            const video = entry.target;
            if (entry.isIntersecting) {
                video.play().catch(() => {});
            } else {
                video.pause();
            }
        });
    }, options);
}

// Event Listeners
function initEventListeners() {
    // Menu Sidebar
    const menuBtn = document.getElementById('menu-btn');
    const closeSidebarBtn = document.getElementById('close-sidebar-btn');
    const sidebar = document.getElementById('sidebar');
    const sidebarBackdrop = document.getElementById('sidebar-backdrop');
    
    const toggleSidebar = () => {
        sidebar.classList.toggle('hidden');
        sidebarBackdrop.classList.toggle('hidden');
    };
    
    menuBtn.addEventListener('click', toggleSidebar);
    closeSidebarBtn.addEventListener('click', toggleSidebar);
    sidebarBackdrop.addEventListener('click', toggleSidebar);

    // Search & Autocomplete suggestions
    const searchForm = document.getElementById('search-form');
    const searchInput = document.getElementById('search-input');
    const suggestionsBox = document.getElementById('search-suggestions');
    let debounceTimer = null;
    let currentSuggestions = [];
    let activeSuggestionIndex = -1;

    searchForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const query = searchInput.value.trim();
        if (query) {
            searchInput.value = '';
            suggestionsBox.classList.add('hidden');
            await loadSearchResults(query);
            // Close sidebar backdrop on mobile
            sidebar.classList.add('hidden');
            sidebarBackdrop.classList.add('hidden');
        }
    });

    const fetchSuggestions = async (val) => {
        if (!val) {
            suggestionsBox.innerHTML = '';
            suggestionsBox.classList.add('hidden');
            return;
        }
        try {
            const isNsfwAllowed = state.nsfwFilter !== 'SFW';
            let data;
            
            if (window.location.protocol === 'file:') {
                data = await queryGraphQL('searchSubreddits', {
                    data: {
                        query: val,
                        limit: 8,
                        pageIndex: 1,
                        isNsfw: isNsfwAllowed
                    }
                });
            } else {
                let endpoint = `/api/search?q=${encodeURIComponent(val)}&nsfw=${isNsfwAllowed}`;
                const res = await fetch(endpoint);
                if (res.ok) {
                    const json = await res.json();
                    data = json.data;
                }
            }
            
            if (data && data.searchSubreddits) {
                currentSuggestions = data.searchSubreddits;
                renderSuggestions(currentSuggestions);
            }
        } catch (err) {
            console.error('Failed to fetch autocomplete suggestions:', err);
        }
    };

    const renderSuggestions = (suggestions) => {
        if (!suggestions || suggestions.length === 0) {
            suggestionsBox.innerHTML = '<div class="suggestion-item">No groups found</div>';
            suggestionsBox.classList.remove('hidden');
            activeSuggestionIndex = -1;
            return;
        }
        
        suggestionsBox.innerHTML = suggestions.map((item, idx) => `
            <div class="suggestion-item" data-index="${idx}" data-url="${item.url}">
                <div class="suggestion-left">
                    <span class="suggestion-title">
                        r/${item.title}
                        ${item.is_nsfw ? '<span class="suggestion-badge-nsfw">18+</span>' : ''}
                    </span>
                </div>
            </div>
        `).join('');
        
        suggestionsBox.classList.remove('hidden');
        activeSuggestionIndex = -1;
        
        suggestionsBox.querySelectorAll('.suggestion-item').forEach(el => {
            el.addEventListener('click', (e) => {
                const subUrl = el.dataset.url;
                if (subUrl) {
                    loadSubreddit(subUrl);
                    searchInput.value = '';
                    suggestionsBox.classList.add('hidden');
                }
            });
        });
    };

    searchInput.addEventListener('input', (e) => {
        const val = e.target.value.trim();
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            fetchSuggestions(val);
        }, 250);
    });

    searchInput.addEventListener('keydown', (e) => {
        const items = suggestionsBox.querySelectorAll('.suggestion-item');
        if (suggestionsBox.classList.contains('hidden') || !items.length) return;
        
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            activeSuggestionIndex = (activeSuggestionIndex + 1) % items.length;
            highlightSuggestion(items);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            activeSuggestionIndex = (activeSuggestionIndex - 1 + items.length) % items.length;
            highlightSuggestion(items);
        } else if (e.key === 'Enter') {
            if (activeSuggestionIndex >= 0 && activeSuggestionIndex < items.length) {
                e.preventDefault();
                items[activeSuggestionIndex].click();
            }
        } else if (e.key === 'Escape') {
            suggestionsBox.classList.add('hidden');
        }
    });

    const highlightSuggestion = (items) => {
        items.forEach(el => el.classList.remove('active'));
        if (activeSuggestionIndex >= 0 && activeSuggestionIndex < items.length) {
            const item = items[activeSuggestionIndex];
            item.classList.add('active');
            item.scrollIntoView({ block: 'nearest' });
        }
    };

    document.addEventListener('click', (e) => {
        if (!searchInput.contains(e.target) && !suggestionsBox.contains(e.target)) {
            suggestionsBox.classList.add('hidden');
        }
    });

    // Content/NSFW filters
    const nsfwSelect = document.getElementById('nsfw-filter-select');
    nsfwSelect.addEventListener('change', (e) => {
        state.nsfwFilter = e.target.value;
        saveSettings();
        loadCategoriesFilter();
        reloadFeed();
    });

    // Toolbar selectors
    document.getElementById('sort-select').addEventListener('change', (e) => {
        state.sortBy = e.target.value;
        reloadFeed();
    });

    document.getElementById('filter-select').addEventListener('change', (e) => {
        state.filter = e.target.value;
        reloadFeed();
    });

    // Column layouts
    const colButtons = document.querySelectorAll('.col-btn');
    colButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            colButtons.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            const cols = parseInt(btn.dataset.cols);
            updateColumns(cols);
        });
    });

    // Star Subreddit button
    const starSubBtn = document.getElementById('star-sub-btn');
    starSubBtn.addEventListener('click', toggleSubredditStar);

    // Popular Subreddits tags
    document.querySelectorAll('.sub-tag').forEach(tag => {
        tag.addEventListener('click', () => {
            loadSubreddit(tag.dataset.name);
            toggleSidebar();
        });
    });

    // Immersive Viewer keys & buttons
    const viewer = document.getElementById('viewer-modal');
    document.getElementById('viewer-back-btn').addEventListener('click', closeViewer);
    document.getElementById('prev-btn').addEventListener('click', showPrevPost);
    document.getElementById('next-btn').addEventListener('click', showNextPost);
    document.getElementById('viewer-immersive-btn').addEventListener('click', toggleImmersiveMode);
    document.getElementById('viewer-save-btn').addEventListener('click', togglePostSave);

    viewer.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeViewer();
        if (e.key === 'ArrowLeft') showPrevPost();
        if (e.key === 'ArrowRight') showNextPost();
        if (e.key === 'f' || e.key === 'F') toggleImmersiveMode();
    });

    // Immersive Double Tap Gesture on Viewer Stage
    const stage = document.getElementById('viewer-stage');
    let lastTap = 0;
    stage.addEventListener('click', (e) => {
        const now = new Date().getTime();
        const timesince = now - lastTap;
        if ((timesince < 300) && (timesince > 0)) {
            toggleImmersiveMode();
        }
        lastTap = now;
    });

    // Swipe Gesture Controls on Mobile for Viewer
    let touchstartX = 0;
    let touchendX = 0;
    
    stage.addEventListener('touchstart', e => {
        touchstartX = e.changedTouches[0].screenX;
    }, {passive: true});

    stage.addEventListener('touchend', e => {
        touchendX = e.changedTouches[0].screenX;
        handleSwipe();
    }, {passive: true});

    function handleSwipe() {
        const threshold = 50;
        if (touchendX < touchstartX - threshold) {
            showNextPost(); // Swipe Left -> Next
        }
        if (touchendX > touchstartX + threshold) {
            showPrevPost(); // Swipe Right -> Prev
        }
    }

    // Scroll infinite handler
    window.addEventListener('scroll', () => {
        if (state.loading || !state.hasMore) return;
        
        // Load more when 80% down the page
        if ((window.innerHeight + window.scrollY) >= document.documentElement.scrollHeight * 0.8) {
            fetchSubredditChildren();
        }
    });

    // Initialize user authentication and category menu events
    initUserAuthAndCategoryEvents();
}

// Columns updater
function updateColumns(cols) {
    state.gridCols = cols;
    localStorage.setItem('gridCols', cols);
    const grid = document.getElementById('media-grid');
    grid.style.setProperty('--cols', cols);
    
    // Set active button
    document.querySelectorAll('.col-btn').forEach(btn => {
        if (parseInt(btn.dataset.cols) === cols) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

// Load Subreddit Entry
async function loadSubreddit(name) {
    showToast(`Loading r/${name}...`);
    state.currentSubreddit = name;
    state.iterator = null;
    state.subredditId = null;
    state.posts = [];
    state.hasMore = true;
    state.isCollection = false;
    
    document.getElementById('media-grid').innerHTML = '';
    
    // Toggle active state in popular sub tags
    document.querySelectorAll('.sub-tag').forEach(tag => {
        if (tag.dataset.name.toLowerCase() === name.toLowerCase()) {
            tag.classList.add('active');
        } else {
            tag.classList.remove('active');
        }
    });

    // Check Star Status
    updateSubredditStarUI();

    try {
        state.loading = true;
        document.getElementById('loading-indicator').classList.remove('hidden');

        // Fetch initial subreddit details & first children page
        const response = await queryGraphQL('SubredditQuery', {
            url: `/r/${name}`,
            filter: state.filter === 'ALL' ? null : state.filter,
            sortBy: state.sortBy === 'OLD' ? 'NEW' : state.sortBy, // Oldest queries via 'NEW' and client sorts
            limit: 50,
            iterator: null
        });

        const sub = response.getSubreddit;
        if (!sub) {
            showToast(`Subreddit r/${name} not found on Scrolller.`, 4000);
            state.hasMore = false;
            document.getElementById('loading-indicator').classList.add('hidden');
            state.loading = false;
            return;
        }

        state.subredditId = sub.id;
        state.isNsfwSubreddit = sub.isNsfw;
        
        // Show Sub Banner
        renderSubBanner(sub);

        // Star button visibility
        document.getElementById('star-sub-btn').classList.remove('hidden');

        // Process first page children
        if (sub.children && sub.children.items) {
            state.iterator = sub.children.iterator;
            processAndAppendPosts(sub.children.items);
        } else {
            state.hasMore = false;
        }

    } catch (err) {
        console.error("Subreddit fetch error", err);
        showToast("Error loading subreddit. Try again or check connection.");
        state.hasMore = false;
    } finally {
        document.getElementById('loading-indicator').classList.add('hidden');
        state.loading = false;
    }
}

// Fetch Next Children Page (Infinite Scroll)
async function fetchSubredditChildren() {
    if (!state.subredditId || !state.hasMore) return;
    
    try {
        state.loading = true;
        document.getElementById('loading-indicator').classList.remove('hidden');

        if (state.isCollection) {
            const response = await queryGraphQL('GetCollection', {
                url: state.currentSubreddit,
                filter: state.filter === 'ALL' ? null : state.filter,
                sortBy: state.sortBy === 'OLD' ? 'NEW' : state.sortBy,
                limit: 50,
                iterator: state.iterator
            });
            const colData = response.getCollection;
            if (colData && colData.children && colData.children.items && colData.children.items.length > 0) {
                state.iterator = colData.children.iterator;
                processAndAppendPosts(colData.children.items);
                if (!state.iterator) {
                    state.hasMore = false;
                    document.getElementById('feed-end').classList.remove('hidden');
                }
            } else {
                state.hasMore = false;
                document.getElementById('feed-end').classList.remove('hidden');
            }
        } else {
            // Resolve NSFW query parameter type
            // NsfwFilter options: SFW, NSFW, ALL
            let resolvedNsfwFilter = 'SFW';
            if (state.nsfwFilter === 'NSFW') resolvedNsfwFilter = 'NSFW';
            if (state.nsfwFilter === 'ALL' || state.isNsfwSubreddit) resolvedNsfwFilter = 'ALL';

            const response = await queryGraphQL('SubredditChildrenQuery', {
                subredditId: state.subredditId,
                filter: state.filter === 'ALL' ? null : state.filter,
                sortBy: state.sortBy === 'OLD' ? 'NEW' : state.sortBy,
                limit: 50,
                iterator: state.iterator,
                isNsfw: resolvedNsfwFilter
            });

            const childData = response.getSubredditChildren;
            if (childData && childData.items && childData.items.length > 0) {
                state.iterator = childData.iterator;
                processAndAppendPosts(childData.items);
                if (!state.iterator) {
                    state.hasMore = false;
                    document.getElementById('feed-end').classList.remove('hidden');
                }
            } else {
                state.hasMore = false;
                document.getElementById('feed-end').classList.remove('hidden');
            }
        }

    } catch (err) {
        console.error("Fetch next page children failed", err);
        state.hasMore = false;
    } finally {
        document.getElementById('loading-indicator').classList.add('hidden');
        state.loading = false;
    }
}

// Reload current feed
function reloadFeed() {
    document.getElementById('feed-end').classList.add('hidden');
    loadSubreddit(state.currentSubreddit);
}

// GraphQL Fetch Helper (Proxy vs Direct)
async function queryGraphQL(opname, variables) {
    const queryMap = {
        SubredditQuery: `
            query SubredditQuery($url: String!, $iterator: String, $sortBy: GallerySortBy, $filter: GalleryFilter, $limit: Int!) {
                getSubreddit(data: {url: $url, iterator: $iterator, filter: $filter, limit: $limit, sortBy: $sortBy}) {
                    id url title secondaryTitle description createdAt isNsfw subscribers itemCount
                    banner { url width height isOptimized }
                    children {
                        iterator items {
                            id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt
                            albumContent { mediaSources { url width height isOptimized } }
                            mediaSources { url width height isOptimized }
                        }
                    }
                }
            }
        `,
        SubredditChildrenQuery: `
            query SubredditChildrenQuery($subredditId: Int!, $iterator: String, $filter: GalleryFilter, $sortBy: GallerySortBy, $limit: Int!, $isNsfw: NsfwFilter!) {
                getSubredditChildren(data: {subredditId: $subredditId, iterator: $iterator, filter: $filter, sortBy: $sortBy, limit: $limit, nsfw: $isNsfw}) {
                    iterator items {
                        id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt
                        albumContent { mediaSources { url width height isOptimized } }
                        mediaSources { url width height isOptimized }
                    }
                }
            }
        `,
        searchSubreddits: `
            query SearchSubreddits($data: SearchSubredditsInput!) {
                searchSubreddits(data: $data) {
                    id url title description item_count is_nsfw
                }
            }
        `,
        GetLoggedInUser: `
            query GetLoggedInUser {
                getLoggedInUser {
                    id
                    username
                    email
                }
            }
        `,
        GetUserCollections: `
            query GetUserCollections {
                getUserCollections {
                    id
                    url
                    title
                    isNsfw
                }
            }
        `,
        GetCollection: `
            query GetCollection($id: Int!, $iterator: String, $sortBy: GallerySortBy, $filter: GalleryFilter, $limit: Int!) {
                getCollection(data: {id: $id, iterator: $iterator, filter: $filter, limit: $limit, sortBy: $sortBy}) {
                    id url title createdAt isNsfw itemsCount
                    children {
                        iterator items {
                            id url title subredditId subredditTitle subredditUrl redditPath isNsfw hasAudio createdAt
                            albumContent { mediaSources { url width height isOptimized } }
                            mediaSources { url width height isOptimized }
                        }
                    }
                }
            }
        `,
        GetCategories: `
            query GetCategories($is_nsfw: Boolean!) {
                categories(data: { is_nsfw: $is_nsfw }) {
                    title
                }
            }
        `,
        GetCategory: `
            query GetCategory($url: String!) {
                getCategory(data: { url: $url }) {
                    id
                    url
                    title
                    isNsfw
                }
            }
        `,
        GetCategorySubreddits: `
            query GetCategorySubreddits($categoryId: Int!) {
                getCategorySubreddits(data: { categoryId: $categoryId }) {
                    subreddits {
                        subredditUrl
                    }
                }
            }
        `
    };

    const payload = {
        query: queryMap[opname],
        variables: variables
    };

    if (state.token) {
        payload.authorization = state.token;
    }

    // Determine target URL: WebView local files can fetch directly; browser pages must use proxy due to CORS.
    let endpoint = 'https://api.scrolller.com/admin';
    let headers = { 'Content-Type': 'application/json' };

    if (window.location.protocol !== 'file:' && !window.location.hostname.includes('127.0.0.1') && !window.location.hostname.includes('localhost')) {
        // We are on a normal HTTP site, use proxy to bypass CORS
        endpoint = '/api/graphql';
    } else if (window.location.hostname.includes('localhost') || window.location.hostname.includes('127.0.0.1')) {
        // Local Python development server endpoint
        endpoint = '/api/graphql';
    }

    const response = await fetch(endpoint, {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(payload)
    });

    if (!response.ok) {
        throw new Error(`HTTP network error: ${response.status}`);
    }

    const json = await response.json();
    if (json.errors) {
        throw new Error(json.errors[0].message);
    }

    return json.data;
}

// Process and Sort Media Assets by Highest Quality
function processAndAppendPosts(newItems) {
    const validItems = newItems.filter(item => {
        // Ensure item has some media sources
        const hasMedia = item.mediaSources && item.mediaSources.length > 0;
        const hasAlbum = item.albumContent && item.albumContent.length > 0;
        return hasMedia || hasAlbum;
    });

    // Map items to calculate highest quality assets
    const processedItems = validItems.map(item => {
        let displayMedia = null;
        let isAlbum = false;
        let albumFiles = [];
        let isVideo = false;
        let posterUrl = null;
        let mediaWidth = 300;
        let mediaHeight = 400;

        if (item.albumContent && item.albumContent.length > 0) {
            isAlbum = true;
            let albumWidth = 300;
            let albumHeight = 400;
            albumFiles = item.albumContent.map(slide => {
                const best = getHighestQuality(slide.mediaSources);
                if (best) {
                    albumWidth = best.width;
                    albumHeight = best.height;
                }
                return best ? best.url : null;
            }).filter(u => u !== null);
            displayMedia = albumFiles[0];
            mediaWidth = albumWidth;
            mediaHeight = albumHeight;
        } else {
            // Check if there are any MP4/WEBM files in mediaSources to classify as video
            const videoSources = item.mediaSources.filter(src => src.url.includes('.mp4') || src.url.includes('.webm'));
            if (videoSources.length > 0) {
                isVideo = true;
                const bestVideo = getHighestQuality(videoSources);
                displayMedia = bestVideo ? bestVideo.url : null;
                mediaWidth = bestVideo ? bestVideo.width : 300;
                mediaHeight = bestVideo ? bestVideo.height : 400;
                
                // Fetch best webp or image source as the poster cover
                const imageSources = item.mediaSources.filter(src => !src.url.includes('.mp4') && !src.url.includes('.webm'));
                const bestImage = getHighestQuality(imageSources);
                posterUrl = bestImage ? bestImage.url : null;
            } else {
                const best = getHighestQuality(item.mediaSources);
                displayMedia = best ? best.url : null;
                mediaWidth = best ? best.width : 300;
                mediaHeight = best ? best.height : 400;
            }
        }

        return {
            ...item,
            displayMedia,
            isAlbum,
            albumFiles,
            isVideo,
            posterUrl,
            width: mediaWidth,
            height: mediaHeight
        };
    }).filter(item => item.displayMedia !== null);

    // Merge into local list
    state.posts = [...state.posts, ...processedItems];

    // Client-side Sort support for "Oldest" (createdAt ascending)
    if (state.sortBy === 'OLD') {
        state.posts.sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0));
        // Clear grid and re-render all to show oldest first in order
        document.getElementById('media-grid').innerHTML = '';
        state.posts.forEach(post => renderCard(post));
    } else {
        // Standard append for normal sort directions
        processedItems.forEach(post => renderCard(post));
    }
}

// Highest Quality Selector (Max Width, then prefer Unoptimized)
function getHighestQuality(sources) {
    if (!sources || sources.length === 0) return null;
    const sorted = [...sources].sort((a, b) => {
        // Sort by width descending
        if (b.width !== a.width) {
            return b.width - a.width;
        }
        // Prefer unoptimized (original file) over optimized versions
        return (a.isOptimized ? 1 : 0) - (b.isOptimized ? 0 : 1);
    });
    return sorted[0];
}

// Render Subreddit Banner UI
function renderSubBanner(sub) {
    const banner = document.getElementById('sub-banner');
    banner.classList.remove('hidden');
    
    document.getElementById('sub-title').textContent = `r/${sub.title}`;
    document.getElementById('sub-description').textContent = sub.description || `Welcome to r/${sub.title}`;
    document.getElementById('sub-subscribers').textContent = `${formatNumber(sub.subscribers)} Subscribers`;
    document.getElementById('sub-item-count').textContent = `${formatNumber(sub.itemCount)} Media items`;
    
    if (sub.banner && sub.banner.url) {
        banner.style.backgroundImage = `linear-gradient(rgba(0,0,0,0.65), rgba(0,0,0,0.85)), url('${sub.banner.url}')`;
    } else {
        banner.style.backgroundImage = `linear-gradient(rgba(0,0,0,0.65), rgba(0,0,0,0.85)), linear-gradient(135deg, #1f2833 0%, #0b0c10 100%)`;
    }
}

// Render Single Card on Main Grid
function renderCard(post) {
    const grid = document.getElementById('media-grid');
    const isVideo = post.isVideo;
    
    // Calculate aspect ratio clamped between 50% and 180%
    let ratio = (post.height && post.width) ? (post.height / post.width * 100) : 130;
    ratio = Math.max(50, Math.min(180, ratio)).toFixed(2);
    
    const card = document.createElement('div');
    card.className = 'media-card';
    card.dataset.id = post.id;

    // Badges
    let badgesHtml = '';
    if (post.isNsfw) {
        badgesHtml += `<span class="card-badge badge-nsfw">18+</span>`;
    }
    if (post.isAlbum) {
        badgesHtml += `<span class="card-badge badge-album">ALBUM (${post.albumFiles.length})</span>`;
    } else if (isVideo) {
        badgesHtml += `<span class="card-badge badge-video">VIDEO</span>`;
    }

    // Media element
    let mediaHtml = '';
    if (isVideo) {
        const posterAttr = post.posterUrl ? `poster="${post.posterUrl}"` : '';
        mediaHtml = `
            <video class="card-media" loop muted playsinline autoplay ${posterAttr} preload="metadata">
                <source src="${post.displayMedia}" type="video/mp4">
            </video>
            <button class="card-volume-btn" aria-label="Mute Toggle">
                ${icons.volumeMute}
            </button>
        `;
    } else {
        mediaHtml = `<img class="card-media" src="${post.displayMedia}" alt="${post.title}" loading="lazy">`;
    }

    // Save/Star Post Check
    const isSaved = state.favorites.posts.some(p => p.id === post.id);
    const saveIcon = isSaved ? '#ffb400' : 'currentColor';
    const fillStar = isSaved ? 'style="fill: #ffb400"' : '';

    card.innerHTML = `
        <div class="card-media-wrapper ${isVideo ? 'video-media' : ''}" style="padding-top: ${ratio}%;">
            ${badgesHtml}
            ${mediaHtml}
        </div>
        <div class="card-info">
            <div class="card-title">${post.title || 'Untitled Post'}</div>
            <div class="card-footer">
                <a href="https://reddit.com${post.redditPath}" target="_blank" class="card-sub" rel="noopener">r/${post.subredditTitle}</a>
                <button class="card-save-btn" aria-label="Save post">
                    <svg viewBox="0 0 24 24" width="18" height="18" ${fillStar}>
                        <path d="M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" fill="${saveIcon}"/>
                    </svg>
                </button>
            </div>
        </div>
    `;

    // Click handler to open fullscreen viewer
    card.querySelector('.card-media-wrapper').addEventListener('click', () => {
        const index = state.posts.findIndex(p => p.id === post.id);
        openViewer(index);
    });

    // Mute/Unmute audio handler for grid video cards
    if (isVideo) {
        const video = card.querySelector('video');
        const volBtn = card.querySelector('.card-volume-btn');
        volBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            video.muted = !video.muted;
            volBtn.innerHTML = video.muted ? icons.volumeMute : icons.volumeUp;
        });
        
        // Register observer to auto play when scrolled in view
        videoObserver.observe(video);
    }

    // Star post click handler
    card.querySelector('.card-save-btn').addEventListener('click', (e) => {
        e.stopPropagation();
        togglePostSaveFromCard(post, card.querySelector('.card-save-btn svg path'));
    });

    grid.appendChild(card);
}

// Immersive Fullscreen Viewer Controller
function openViewer(index) {
    if (index < 0 || index >= state.posts.length) return;
    
    state.currentViewerIndex = index;
    const viewer = document.getElementById('viewer-modal');
    viewer.classList.remove('hidden');
    viewer.focus();
    
    // Disable body scroll
    document.body.style.overflow = 'hidden';
    
    renderViewerPost();
}

function closeViewer() {
    const viewer = document.getElementById('viewer-modal');
    viewer.classList.add('hidden');
    
    // Stop any playing video in the viewer stage
    const stage = document.getElementById('viewer-stage');
    const videos = stage.querySelectorAll('video');
    videos.forEach(v => v.pause());
    stage.innerHTML = '';
    
    // Restore body scroll
    document.body.style.overflow = 'auto';
}

function showNextPost() {
    if (state.currentViewerIndex < state.posts.length - 1) {
        openViewer(state.currentViewerIndex + 1);
    } else if (state.hasMore) {
        // Load more items from server to let viewer scroll infinitely!
        fetchSubredditChildren().then(() => {
            if (state.currentViewerIndex < state.posts.length - 1) {
                openViewer(state.currentViewerIndex + 1);
            }
        });
    }
}

function showPrevPost() {
    if (state.currentViewerIndex > 0) {
        openViewer(state.currentViewerIndex - 1);
    }
}

function renderViewerPost() {
    const post = state.posts[state.currentViewerIndex];
    const stage = document.getElementById('viewer-stage');
    stage.innerHTML = ''; // Clear stage

    // Set post details
    document.getElementById('viewer-post-title').textContent = post.title || 'Untitled Post';
    document.getElementById('viewer-post-sub').textContent = `r/${post.subredditTitle}`;
    document.getElementById('viewer-index').textContent = `${state.currentViewerIndex + 1} / ${state.posts.length}`;

    // Update Save post button star UI
    const isSaved = state.favorites.posts.some(p => p.id === post.id);
    if (isSaved) {
        document.getElementById('viewer-save-btn').querySelector('.post-star-outline').classList.add('hidden');
        document.getElementById('viewer-save-btn').querySelector('.post-star-filled').classList.remove('hidden');
    } else {
        document.getElementById('viewer-save-btn').querySelector('.post-star-outline').classList.remove('hidden');
        document.getElementById('viewer-save-btn').querySelector('.post-star-filled').classList.add('hidden');
    }

    // Render Media Content based on type
    if (post.isAlbum) {
        // It's an Album carousel
        const albumContainer = document.createElement('div');
        albumContainer.className = 'viewer-album-container';
        
        let slideIndex = 0;
        const renderSlide = () => {
            albumContainer.innerHTML = `
                <span class="album-counter">Slide ${slideIndex + 1} of ${post.albumFiles.length}</span>
                <img class="viewer-media" src="${post.albumFiles[slideIndex]}" alt="${post.title}">
            `;
        };
        renderSlide();

        // Arrow keys inside slide
        const slideLeft = () => {
            if (slideIndex > 0) {
                slideIndex--;
                renderSlide();
            }
        };
        const slideRight = () => {
            if (slideIndex < post.albumFiles.length - 1) {
                slideIndex++;
                renderSlide();
            }
        };

        // Click on left/right half of screen to slide inside albums
        albumContainer.addEventListener('click', (e) => {
            const width = window.innerWidth;
            if (e.clientX < width * 0.3) {
                slideLeft();
            } else if (e.clientX > width * 0.7) {
                slideRight();
            }
        });

        stage.appendChild(albumContainer);

    } else if (post.isVideo) {
        // It's a Video
        const video = document.createElement('video');
        video.className = 'viewer-media';
        video.src = post.displayMedia;
        if (post.posterUrl) {
            video.poster = post.posterUrl;
        }
        video.controls = true;
        video.autoplay = true;
        video.loop = true;
        video.playsInline = true;
        stage.appendChild(video);
    } else {
        // It's an Image
        const img = document.createElement('img');
        img.className = 'viewer-media';
        img.src = post.displayMedia;
        img.alt = post.title;
        stage.appendChild(img);
    }
}

// Toggle Immersive Fullscreen Mode
function toggleImmersiveMode() {
    state.immersiveMode = !state.immersiveMode;
    const viewer = document.getElementById('viewer-modal');
    if (state.immersiveMode) {
        viewer.classList.add('immersive-mode');
        showToast("Immersive Fullscreen Mode (Double tap to restore)", 2000);
    } else {
        viewer.classList.remove('immersive-mode');
    }
}

// Star / Bookmark Subreddit Management (No auth needed)
function toggleSubredditStar() {
    const name = state.currentSubreddit;
    const index = state.favorites.subreddits.indexOf(name);
    
    if (index === -1) {
        state.favorites.subreddits.push(name);
        showToast(`Added r/${name} to Favorites!`);
    } else {
        state.favorites.subreddits.splice(index, 1);
        showToast(`Removed r/${name} from Favorites.`);
    }

    saveSettings();
    updateSubredditStarUI();
    renderFavorites();
}

function updateSubredditStarUI() {
    const starBtn = document.getElementById('star-sub-btn');
    const outline = starBtn.querySelector('.star-outline');
    const filled = starBtn.querySelector('.star-filled');
    
    const isStarred = state.favorites.subreddits.includes(state.currentSubreddit);
    if (isStarred) {
        outline.classList.add('hidden');
        filled.classList.remove('hidden');
    } else {
        outline.classList.remove('hidden');
        filled.classList.add('hidden');
    }
}

// Star / Bookmark Single Post
function togglePostSave() {
    const post = state.posts[state.currentViewerIndex];
    const index = state.favorites.posts.findIndex(p => p.id === post.id);

    if (index === -1) {
        // Save post
        state.favorites.posts.push({
            id: post.id,
            title: post.title,
            displayMedia: post.displayMedia,
            isAlbum: post.isAlbum,
            albumFiles: post.albumFiles,
            subredditTitle: post.subredditTitle,
            redditPath: post.redditPath
        });
        showToast("Post bookmarked!");
    } else {
        // Unsave post
        state.favorites.posts.splice(index, 1);
        showToast("Bookmark removed.");
    }

    saveSettings();
    renderViewerPost(); // Re-render to update filled star
    renderFavorites();
}

// Star/Save post directly from grid card
function togglePostSaveFromCard(post, pathEl) {
    const index = state.favorites.posts.findIndex(p => p.id === post.id);
    
    if (index === -1) {
        state.favorites.posts.push({
            id: post.id,
            title: post.title,
            displayMedia: post.displayMedia,
            isAlbum: post.isAlbum,
            albumFiles: post.albumFiles,
            subredditTitle: post.subredditTitle,
            redditPath: post.redditPath
        });
        pathEl.style.fill = '#ffb400';
        showToast("Post bookmarked!");
    } else {
        state.favorites.posts.splice(index, 1);
        pathEl.removeAttribute('style');
        showToast("Bookmark removed.");
    }

    saveSettings();
    renderFavorites();
}

// Render favorites lists in sidebar
function renderFavorites() {
    // Render starred subreddits
    const subList = document.getElementById('starred-subreddits-list');
    subList.innerHTML = '';
    
    if (state.favorites.subreddits.length === 0) {
        subList.innerHTML = `<li class="empty-list-msg">No starred subreddits.</li>`;
    } else {
        state.favorites.subreddits.forEach(name => {
            const li = document.createElement('li');
            li.innerHTML = `
                <a href="#">r/${name}</a>
                <button class="icon-btn remove-sub-star" data-name="${name}" style="width: 32px; height: 32px;">
                    <svg viewBox="0 0 24 24" width="16" height="16"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" fill="currentColor"/></svg>
                </button>
            `;
            li.querySelector('a').addEventListener('click', (e) => {
                e.preventDefault();
                loadSubreddit(name);
                document.getElementById('sidebar').classList.add('hidden');
                document.getElementById('sidebar-backdrop').classList.add('hidden');
            });
            li.querySelector('.remove-sub-star').addEventListener('click', (e) => {
                e.stopPropagation();
                const index = state.favorites.subreddits.indexOf(name);
                if (index > -1) {
                    state.favorites.subreddits.splice(index, 1);
                    saveSettings();
                    updateSubredditStarUI();
                    renderFavorites();
                }
            });
            subList.appendChild(li);
        });
    }

    // Render bookmarked posts thumb grid
    const postsGrid = document.getElementById('saved-posts-grid');
    postsGrid.innerHTML = '';
    
    if (state.favorites.posts.length === 0) {
        postsGrid.innerHTML = `<span class="empty-list-msg">No saved posts.</span>`;
    } else {
        state.favorites.posts.forEach(post => {
            const isVideo = post.isVideo;
            const thumb = document.createElement('img');
            thumb.className = 'saved-thumb';
            thumb.src = isVideo ? (post.posterUrl || 'icon.png') : post.displayMedia;
            thumb.alt = post.title;
            thumb.title = post.title;

            thumb.addEventListener('click', () => {
                // To load bookmarked post in viewer, we override the viewer list with our bookmarks list
                // and open the selected post!
                const origPosts = [...state.posts];
                state.posts = [...state.favorites.posts];
                openViewer(state.favorites.posts.findIndex(p => p.id === post.id));
                
                // When viewer closes, we restore state.posts
                const oldClose = closeViewer;
                closeViewer = function() {
                    oldClose();
                    state.posts = origPosts;
                    closeViewer = oldClose; // restore original
                };
            });
            postsGrid.appendChild(thumb);
        });
    }
}

// Utility formatting helpers
function formatNumber(num) {
    if (!num) return '0';
    if (num >= 1000000) {
        return (num / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
    }
    if (num >= 1000) {
        return (num / 1000).toFixed(1).replace(/\.0$/, '') + 'K';
    }
    return num.toString();
}

// Toast Notifications
let toastTimeout;
function showToast(message, duration = 3000) {
    const toast = document.getElementById('toast');
    document.getElementById('toast-message').textContent = message;
    
    toast.classList.remove('hidden');
    
    clearTimeout(toastTimeout);
    toastTimeout = setTimeout(() => {
        toast.classList.add('hidden');
    }, duration);
}

// Sync User Profile (token verification & data load)
async function syncUserProfile(token) {
    try {
        state.token = token;
        localStorage.setItem('scrolller_token', token);
        
        // Fetch user profile info
        const profileData = await queryGraphQL('GetLoggedInUser', {});
        if (profileData && profileData.getLoggedInUser) {
            state.userProfile = profileData.getLoggedInUser;
            document.getElementById('logged-user-name').textContent = state.userProfile.username;
            document.getElementById('user-profile-info').classList.remove('hidden');
            document.getElementById('user-profile-guest').classList.add('hidden');
            
            showToast(`Synced Scrolller account: ${state.userProfile.username}`);
            
            // Load user collections
            fetchUserCollections();
        } else {
            throw new Error("Session profile query failed.");
        }
    } catch (err) {
        console.error("Authentication failed:", err);
        // Clear token
        state.token = '';
        state.userProfile = null;
        localStorage.removeItem('scrolller_token');
        document.getElementById('user-profile-info').classList.add('hidden');
        document.getElementById('user-profile-guest').classList.remove('hidden');
        showToast("Scrolller account sync failed.");
    }
}

// Fetch Logged-in User Collections from Scrolller
async function fetchUserCollections() {
    try {
        const data = await queryGraphQL('GetUserCollections', {});
        if (data && data.getUserCollections) {
            state.userCollections = data.getUserCollections;
            renderUserCollections();
        }
    } catch (err) {
        console.error("Failed to fetch user collections:", err);
    }
}

// Render User Collections List in Sidebar
function renderUserCollections() {
    const list = document.getElementById('user-collections-list');
    const container = document.getElementById('user-collections-section');
    
    if (!state.userCollections || state.userCollections.length === 0) {
        container.classList.add('hidden');
        return;
    }
    
    container.classList.remove('hidden');
    list.innerHTML = state.userCollections.map(col => `
        <li class="sidebar-item" data-id="${col.id}" data-url="${col.url}">
            <span class="sub-link-text">c/${col.title}</span>
            ${col.isNsfw ? '<span class="suggestion-badge-nsfw">18+</span>' : ''}
        </li>
    `).join('');
    
    list.querySelectorAll('.sidebar-item').forEach(item => {
        item.addEventListener('click', () => {
            loadCollection(parseInt(item.dataset.id), item.querySelector('.sub-link-text').textContent);
            // Hide sidebar on mobile
            if (window.innerWidth <= 768) {
                document.getElementById('sidebar').classList.add('hidden');
                document.getElementById('sidebar-backdrop').classList.add('hidden');
            }
        });
    });
}

// Load Scrolller User Collection Feed
async function loadCollection(id, displayName) {
    try {
        state.loading = true;
        state.posts = [];
        state.iterator = null;
        state.hasMore = true;
        state.currentSubreddit = id;
        state.subredditId = id;
        state.isCollection = true;
        
        document.getElementById('media-grid').innerHTML = '';
        document.getElementById('loading-indicator').classList.remove('hidden');
        document.getElementById('feed-end').classList.add('hidden');
        document.getElementById('star-sub-btn').classList.add('hidden');
        
        const response = await queryGraphQL('GetCollection', {
            id: id,
            filter: state.filter === 'ALL' ? null : state.filter,
            sortBy: state.sortBy === 'OLD' ? 'NEW' : state.sortBy,
            limit: 50,
            iterator: null
        });
        
        const col = response.getCollection;
        if (!col) {
            document.getElementById('media-grid').innerHTML = '<div class="empty-list-msg">Collection empty or not found.</div>';
            return;
        }
        
        state.subredditId = col.id;
        state.isNsfwSubreddit = col.isNsfw;
        
        renderSubBanner({
            title: displayName || col.title,
            description: `Personal Scrolller Collection`,
            subscribers: 0,
            itemCount: col.itemsCount || 0,
            banner: null
        });
        
        if (col.children && col.children.items && col.children.items.length > 0) {
            state.iterator = col.children.iterator;
            processAndAppendPosts(col.children.items);
        } else {
            state.hasMore = false;
            document.getElementById('feed-end').classList.remove('hidden');
        }
    } catch (err) {
        console.error("Failed to load collection:", err);
        document.getElementById('media-grid').innerHTML = `<div class="empty-list-msg">Error loading collection: ${err.message}</div>`;
    } finally {
        document.getElementById('loading-indicator').classList.add('hidden');
        state.loading = false;
    }
}

// Load Search Results View (List everything)
async function loadSearchResults(queryText) {
    try {
        state.loading = true;
        state.posts = [];
        state.iterator = null;
        state.hasMore = false;
        state.currentSubreddit = '';
        state.subredditId = null;
        state.isCollection = false;
        
        document.getElementById('media-grid').innerHTML = '';
        document.getElementById('loading-indicator').classList.remove('hidden');
        document.getElementById('feed-end').classList.add('hidden');
        document.getElementById('sub-banner').classList.add('hidden');
        document.getElementById('star-sub-btn').classList.add('hidden');
        
        const isNsfwAllowed = state.nsfwFilter !== 'SFW';
        let data;
        
        if (window.location.protocol === 'file:') {
            data = await queryGraphQL('searchSubreddits', {
                data: {
                    query: queryText,
                    limit: 30,
                    pageIndex: 1,
                    isNsfw: isNsfwAllowed
                }
            });
        } else {
            let endpoint = `/api/search?q=${encodeURIComponent(queryText)}&nsfw=${isNsfwAllowed}`;
            const res = await fetch(endpoint);
            if (res.ok) {
                const json = await res.json();
                data = json.data;
            }
        }
        
        if (data && data.searchSubreddits && data.searchSubreddits.length > 0) {
            renderSubredditList(data.searchSubreddits, queryText);
        } else {
            document.getElementById('media-grid').innerHTML = `
                <div class="empty-list-msg">
                    No groups found matching "${queryText}".
                </div>
            `;
        }
    } catch (err) {
        console.error("Search failed:", err);
        document.getElementById('media-grid').innerHTML = `<div class="empty-list-msg">Search failed: ${err.message}</div>`;
    } finally {
        document.getElementById('loading-indicator').classList.add('hidden');
        state.loading = false;
    }
}

// Render Grid of Subreddit catalog entries
function renderSubredditList(subreddits, queryText) {
    const grid = document.getElementById('media-grid');
    grid.innerHTML = '';
    
    const banner = document.getElementById('sub-banner');
    banner.classList.remove('hidden');
    document.getElementById('sub-title').textContent = queryText.startsWith('Category:') ? queryText : `Search Catalog`;
    document.getElementById('sub-description').textContent = queryText.startsWith('Category:') ? `Explore subreddits listed under this category` : `Subreddits matching "${queryText}"`;
    document.getElementById('sub-subscribers').textContent = `Found ${subreddits.length} subreddits`;
    document.getElementById('sub-item-count').textContent = `Scrolller Index`;
    banner.style.backgroundImage = `linear-gradient(rgba(0,0,0,0.7), rgba(0,0,0,0.95))`;
    
    subreddits.forEach(sub => {
        const card = document.createElement('div');
        card.className = 'subreddit-card glassmorphism';
        
        const desc = sub.description ? sub.description : 'No description available for this subreddit.';
        const items = sub.item_count ? `${formatNumber(sub.item_count)} items` : 'Multiple items';
        
        card.innerHTML = `
            <div class="subreddit-card-header">
                <span class="subreddit-card-title">r/${sub.title}</span>
                ${sub.is_nsfw ? '<span class="suggestion-badge-nsfw">18+</span>' : '<span class="suggestion-subscribers" style="color:var(--accent)">SFW</span>'}
            </div>
            <p class="subreddit-card-desc">${desc}</p>
            <div class="subreddit-card-meta">
                <span class="meta-items">${items}</span>
            </div>
            <button class="subreddit-card-btn">Explore Feed</button>
        `;
        
        card.addEventListener('click', () => {
            loadSubreddit(sub.url);
        });
        
        grid.appendChild(card);
    });
}

// Initialize Auth & Categories Event Listeners
function initUserAuthAndCategoryEvents() {
    const signinModal = document.getElementById('signin-modal');
    const signinTriggerBtn = document.getElementById('signin-trigger-btn');
    const signinCloseBtn = document.getElementById('signin-close-btn');
    const signinSubmitBtn = document.getElementById('signin-submit-btn');
    const signinTokenInput = document.getElementById('signin-token-input');
    const signinError = document.getElementById('signin-error');
    const signoutBtn = document.getElementById('signout-btn');
    
    // Modal controls
    if (signinTriggerBtn) {
        signinTriggerBtn.addEventListener('click', () => {
            signinModal.classList.remove('hidden');
            signinError.classList.add('hidden');
            signinTokenInput.value = '';
        });
    }
    
    if (signinCloseBtn) {
        signinCloseBtn.addEventListener('click', () => {
            signinModal.classList.add('hidden');
        });
    }
    
    // Form submit
    if (signinSubmitBtn) {
        signinSubmitBtn.addEventListener('click', async () => {
            const token = signinTokenInput.value.trim();
            if (!token) {
                signinError.textContent = 'Please paste a token first!';
                signinError.classList.remove('hidden');
                return;
            }
            signinError.classList.add('hidden');
            
            try {
                // Sync user account profile
                await syncUserProfile(token);
                signinModal.classList.add('hidden');
            } catch (err) {
                signinError.textContent = 'Authentication failed. Please verify your token.';
                signinError.classList.remove('hidden');
            }
        });
    }
    
    // Sign out
    if (signoutBtn) {
        signoutBtn.addEventListener('click', () => {
            state.token = '';
            state.userProfile = null;
            state.userCollections = [];
            localStorage.removeItem('scrolller_token');
            
            document.getElementById('user-profile-info').classList.add('hidden');
            document.getElementById('user-profile-guest').classList.remove('hidden');
            document.getElementById('user-collections-section').classList.add('hidden');
            
            showToast("Signed out of Scrolller.");
            reloadFeed();
        });
    }
    
    // Category select filter dropdown listener
    const categorySelect = document.getElementById('category-select');
    if (categorySelect) {
        categorySelect.addEventListener('change', (e) => {
            const cat = e.target.value;
            if (cat === 'ALL') {
                loadSubreddit('funny');
            } else {
                loadCategoryFeeds(cat);
            }
        });
    }
}

// Fetch and populate Category select filter dropdown dynamically from Scrolller API
async function loadCategoriesFilter() {
    try {
        const isNsfw = state.nsfwFilter !== 'SFW';
        const data = await queryGraphQL('GetCategories', { is_nsfw: isNsfw });
        
        const select = document.getElementById('category-select');
        if (!select) return;
        
        // Preserve "All Categories" option
        select.innerHTML = '<option value="ALL">All Categories</option>';
        
        if (data && data.categories) {
            data.categories.forEach(cat => {
                const opt = document.createElement('option');
                opt.value = cat.title;
                opt.textContent = cat.title;
                select.appendChild(opt);
            });
        }
    } catch (err) {
        console.error("Failed to load Scrolller categories list:", err);
    }
}

// Load Scrolller Category feeds (lists category subreddits as exploration directory)
async function loadCategoryFeeds(categoryName) {
    try {
        state.loading = true;
        state.posts = [];
        state.iterator = null;
        state.hasMore = false;
        state.currentSubreddit = '';
        state.subredditId = null;
        state.isCollection = false;
        
        document.getElementById('media-grid').innerHTML = '';
        document.getElementById('loading-indicator').classList.remove('hidden');
        document.getElementById('feed-end').classList.add('hidden');
        document.getElementById('sub-banner').classList.add('hidden');
        document.getElementById('star-sub-btn').classList.add('hidden');
        
        // Generate lowercase hyphenated slug, e.g. "Baby Animals" -> "baby-animals"
        const slug = categoryName.toLowerCase().replace(/\s+/g, '-');
        
        // Query Category details to fetch categoryId
        const catRes = await queryGraphQL('GetCategory', { url: slug });
        const cat = catRes.getCategory;
        if (!cat) {
            document.getElementById('media-grid').innerHTML = `<div class="empty-list-msg">Category "${categoryName}" not found on Scrolller.</div>`;
            return;
        }
        
        // Fetch subreddits listed under this category
        const subRes = await queryGraphQL('GetCategorySubreddits', { categoryId: cat.id });
        if (subRes && subRes.getCategorySubreddits && subRes.getCategorySubreddits.subreddits) {
            // Map subreddits to search-like output items for listing
            const subreddits = subRes.getCategorySubreddits.subreddits.map(s => ({
                title: s.subredditUrl,
                url: s.subredditUrl,
                is_nsfw: cat.isNsfw,
                description: `Official Scrolller Subreddit listed under the "${categoryName}" category directory.`,
                item_count: 0
            }));
            
            renderSubredditList(subreddits, `Category: ${categoryName}`);
        } else {
            document.getElementById('media-grid').innerHTML = `<div class="empty-list-msg">No subreddits found for this category.</div>`;
        }
    } catch (err) {
        console.error("Failed to load category subreddits directory:", err);
        document.getElementById('media-grid').innerHTML = `<div class="empty-list-msg">Error loading category directory: ${err.message}</div>`;
    } finally {
        document.getElementById('loading-indicator').classList.add('hidden');
        state.loading = false;
    }
}
