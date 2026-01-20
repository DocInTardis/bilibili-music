const { createApp } = Vue;

createApp({
    data() {
        return {
            wsConnected: false,
            stompClient: null,
            messages: [],
            userInput: '',
            runMode: '',
            resultLimit: 10,
            preference: '',
            albumOrder: false,
            highRelevanceMode: false,
            playlist: [],
            currentVideoIndex: -1,
            playMode: 'normal',
            shuffleOrder: [],
            shufflePos: 0,
            dragStartIndex: null,
            dragOverIndex: null,
            isPlaying: false,
            playerSrc: '',
            lastPlayerSrc: '',
            audioSrc: '',
            isDownloading: false,
            playRequestSeq: 0,
            agentSummary: '',
            confidence: null,
            recommendationExplanation: null,
            conversations: [],
            currentConversationId: null,
            executionHistoryVisible: false,
            recentExecutions: [],
            selectedExecutionKeys: [],
            executionCompareRows: [],
            observability: null,
            messageSeq: 1,
            whyDetail: null,
            showVideoContent: true,
            feedbackTarget: null,
            feedbackText: '',
            feedbackSending: false,
            playHintVisible: false,
            playHintTimer: null,
            audioCurrentTime: 0,
            audioDuration: 0,
            isSeeking: false,
            isPlaySwitching: false
        };
    },
    computed: {
        playlistCountText() {
            return this.playlist.length ? `共 ${this.playlist.length} 首` : '暂无视频';
        },
        currentVideo() {
            return this.currentVideoIndex >= 0 ? this.playlist[this.currentVideoIndex] : null;
        },
        currentDisplayTitle() {
            if (this.currentVideo) {
                const trackNo = this.currentVideo.trackNo != null ? `#${this.currentVideo.trackNo} ` : '';
                return `${trackNo}${this.getVideoTitle(this.currentVideo)}`;
            }
            return '欢迎使用 Bilibili 音乐助手';
        },
        currentDisplaySummary() {
            return this.agentSummary || '请输入你的音乐需求，系统将自动搜索并构建播放列表。';
        },
        currentDisplayMeta() {
            if (!this.currentVideo) return '等待播放列表...';
            return `${this.currentVideo.author || '未知作者'} · ${this.currentVideo.duration || '未知时长'}`;
        }
    },
    mounted() {
        this.connectWebSocket();
        this.loadConversations();
        try {
            const saved = localStorage.getItem('showVideoContent');
            if (saved !== null) {
                this.showVideoContent = saved === 'true';
            }
        } catch (e) {}
    },
    watch: {
        showVideoContent(value) {
            try {
                localStorage.setItem('showVideoContent', value ? 'true' : 'false');
            } catch (e) {}
            if (value) {
                this.syncVideoWithAudio(true);
            } else {
                this.playerSrc = '';
            }
        }
    },
    methods: {
        messageAvatar(kind) {
            switch (kind) {
                case 'user':
                    return 'U';
                case 'agent':
                    return 'AI';
                case 'stage':
                    return 'ST';
                case 'search':
                    return 'SR';
                case 'stream':
                    return 'RT';
                case 'recommendation':
                    return 'RC';
                default:
                    return '?';
            }
        },
        nextMessageId() {
            return this.messageSeq++;
        },
        pushMessage(message) {
            this.messages.push({ id: this.nextMessageId(), ...message });
            this.$nextTick(this.scrollMessages);
        },
        scrollMessages() {
            const box = this.$refs.messages;
            if (box) {
                box.scrollTop = box.scrollHeight;
            }
        },
        addStatus(text) {
            this.pushMessage({ kind: 'status', text });
        },
        addUser(text) {
            this.pushMessage({ kind: 'user', text });
        },
        addAgent(text) {
            this.pushMessage({ kind: 'agent', text });
        },
        connectWebSocket() {
            this.addStatus('正在连接 WebSocket...');
            if (typeof SockJS === 'undefined') {
                this.addStatus('SockJS 未加载，暂时无法建立连接。');
                return;
            }
            const socket = new SockJS('/ws/chat');
            this.stompClient = Stomp.over(socket);
            this.stompClient.debug = null;
            this.stompClient.connect({}, () => {
                this.wsConnected = true;
                this.addStatus('已连接到服务端');
                this.stompClient.subscribe('/topic/messages', (message) => {
                    try {
                        const data = JSON.parse(message.body);
                        this.handleServerMessage(data);
                    } catch (e) {
                        this.addStatus('消息解析失败');
                    }
                });
            }, () => {
                this.wsConnected = false;
                this.addStatus('连接失败，3 秒后重试...');
                setTimeout(() => this.connectWebSocket(), 3000);
            });
        },
        handleServerMessage(data) {
            if (!data) return;
            if (data.type === 'accepted') {
                const jobId = data.payload && data.payload.jobId ? data.payload.jobId : null;
                const message = jobId ? `${data.content || 'Job accepted'} (jobId=${jobId})` : (data.content || 'Job accepted');
                this.addStatus(message);
                return;
            }
            if (data.type === 'status') {
                const jobId = data.payload && data.payload.jobId ? data.payload.jobId : null;
                this.addStatus(jobId ? `[${jobId}] ${data.content}` : data.content);
                return;
            }
            if (data.type === 'stage_update') {
                this.renderStageInsight(data.stage, data.payload, data.content);
                return;
            }
            if (data.type === 'search_results') {
                this.renderSearchResults(data.payload, data.content);
                return;
            }
            if (data.type === 'stream_update') {
                this.renderVideoJudgementStream(data.stage, data.payload, data.content);
                return;
            }
            if (data.type === 'video_accepted') {
                if (data.videos && data.videos.length > 0) {
                    this.addVideoToPlaylistStreaming(data.videos[0], data.content, data.payload);
                }
                return;
            }
            if (data.type === 'result') {
                if (data.trashVideos && data.trashVideos.length > 0) {
                    this.addRecommendationMessage(data.trashVideos);
                }
                if (data.summary) {
                    this.agentSummary = data.summary;
                    this.addAgent(data.summary);
                }
                if (data.confidence != null) {
                    this.confidence = data.confidence;
                }
                if (data.explanation) {
                    this.recommendationExplanation = data.explanation;
                }
                if (data.videos && data.videos.length > 0) {
                    data.videos.forEach(video => this.addVideoToPlaylistStreaming(video, null));
                }
                return;
            }
            if (data.type === 'error') {
                this.addStatus(`❌ ${data.content || '发生错误'}`);
            }
        },
        renderStageInsight(stage, payload, text) {
            let detail = '';
            if (stage === 'INTENT_UNDERSTANDING' && payload) {
                detail = `目标 ${payload.targetCount || 10} 首`;
                if (payload.albumTitle) detail += ` | Album: ${payload.albumTitle}`;
                if (payload.albumArtist) detail += ` | Artist: ${payload.albumArtist}`;
                if (payload.albumOrder) detail += ' | Order: on';
                if (payload.preference) detail += ` | 风格: ${payload.preference}`;
            } else if (stage === 'KEYWORD_EXTRACTION' && payload) {
                const kw = payload.keywords ? payload.keywords.join(', ') : '';
                detail = `关键词: ${kw}`;
            } else if (stage === 'TARGET_EVALUATION' && payload) {
                detail = `已选 ${payload.actualCount} / 目标 ${payload.targetCount}`;
                if (!payload.enough) {
                    detail += ` | 候选 ${payload.trashCount}`;
                }
            }
            this.pushMessage({
                kind: 'stage',
                title: stage || '阶段更新',
                text: text || '',
                detail
            });
        },
        renderSearchResults(payload, content) {
            const total = payload && payload.totalCount ? payload.totalCount : 0;
            const samples = payload && payload.samples ? payload.samples : [];
            this.pushMessage({
                kind: 'search',
                title: '搜索结果',
                text: content || `共找到 ${total} 条候选`,
                items: samples.slice(0, 5)
            });
        },
        renderVideoJudgementStream(stage, payload, text) {
            const video = payload && payload.video ? payload.video : null;
            const progress = payload && payload.progress ? payload.progress : null;
            const title = video ? video.title : '视频判断';
            const progressText = progress ? `累计 ${progress.accumulatedCount || 0} / 目标 ${progress.targetCount || '?'} 首` : '';
            this.pushMessage({
                kind: 'stream',
                title,
                text: text || '评估中...',
                detail: progressText
            });
        },
        addRecommendationMessage(videos) {
            const items = videos.slice(0, 5);
            const needsConfirm = this.highRelevanceMode;
            const msg = {
                kind: 'recommendation',
                title: '候选推荐',
                text: needsConfirm
                    ? '相关度略低的候选如下，确认后可加入播放列表。'
                    : '自动加入相似推荐到播放列表。',
                items,
                needsConfirm,
                accepted: !needsConfirm
            };
            this.pushMessage(msg);
            if (!needsConfirm) {
                this.acceptRecommendations(msg);
            }
        },
        acceptRecommendations(msg) {
            if (!msg || !msg.items || msg.items.length === 0) return;
            msg.items.forEach(video => this.addVideoToPlaylistStreaming(video, null));
            msg.accepted = true;
            this.addStatus(`已添加 ${msg.items.length} 条候选`);
        },
        addVideoToPlaylistStreaming(video, message, decisionPayload) {
            if (!video) return;
            if (this.playlist.some(v => v.url && video.url && v.url === video.url)) {
                return;
            }
            this.ensureVideoBvid(video);
            if (decisionPayload) {
                video.__decision = decisionPayload;
            }
            this.playlist.push(video);
            if (message) {
                this.addStatus(`✅ ${message}`);
            }
            if (this.playlist.length === 1) {
                this.playVideo(0);
            }
        },
        displayTrackIndex(video, index) {
            if (video && video.trackNo != null) {
                return video.trackNo;
            }
            return index + 1;
        },
        getVideoTitle(video) {
            if (!video) return '未命名视频';
            const custom = typeof video.customTitle === 'string' ? video.customTitle.trim() : '';
            return custom || video.title || '未命名视频';
        },
        getDisplayTitle(video) {
            return this.getVideoTitle(video);
        },
        summarizeTitle(title) {
            if (!title) return '未命名视频';
            let text = title;
            text = text.replace(/【.*?】/g, '');
            text = text.replace(/\[.*?\]/g, '');
            text = text.replace(/\(.*?\)/g, '');
            text = text.replace(/（.*?）/g, '');
            text = text.replace(/《.*?》/g, '');
            text = text.replace(/[「『].*?[」』]/g, '');
            text = text.replace(/\s+/g, ' ').trim();
            text = text.replace(/(完整版|官方|高清|MV|Live|现场|纯音乐|无损|歌词版|完整版)/gi, '').trim();
            const separators = text.split(/[-—|丨·]/).map(part => part.trim()).filter(Boolean);
            if (separators.length >= 2) {
                text = separators[separators.length - 1];
            } else if (separators.length === 1) {
                text = separators[0];
            }
            if (text.length > 24) {
                text = `${text.slice(0, 24)}...`;
            }
            return text || title;
        },
        getCoverUrl(video) {
            if (!video) return '';
            return video.coverUrl || video.cover || video.pic || '';
        },
        getCoverStyle(video) {
            const cover = this.getCoverUrl(video);
            if (!cover) return {};
            return { backgroundImage: `url('${cover}')` };
        },
        renameVideo(video) {
            if (!video) {
                this.addStatus('当前没有可重命名的视频');
                return;
            }
            const current = this.getVideoTitle(video);
            const next = window.prompt('重命名当前视频：', current);
            if (next === null) {
                return;
            }
            const cleaned = next.trim();
            if (cleaned) {
                video.customTitle = cleaned;
                this.addStatus(`已重命名：${cleaned}`);
            } else {
                video.customTitle = '';
                this.addStatus('已清除自定义标题');
            }
        },
        openFeedback(video) {
            if (!video) {
                this.addStatus('当前没有可引用的视频');
                return;
            }
            this.feedbackTarget = video;
            this.feedbackText = '';
        },
        closeFeedback() {
            this.feedbackTarget = null;
            this.feedbackText = '';
            this.feedbackSending = false;
        },
        async submitFeedback() {
            if (!this.feedbackTarget) return;
            const text = (this.feedbackText || '').trim();
            if (!text) {
                this.addStatus('请输入你的看法后再提交');
                return;
            }
            const video = this.feedbackTarget;
            this.ensureVideoBvid(video);
            const title = this.getVideoTitle(video);
            this.addUser(`【引用：${title}】${text}`);
            this.feedbackSending = true;
            try {
                const resp = await fetch('/api/playlist/video-feedback', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        conversationId: this.currentConversationId,
                        bvid: video.bvid || '',
                        title: video.title || '',
                        author: video.author || '',
                        url: video.url || '',
                        comment: text
                    })
                });
                if (!resp.ok) {
                    this.addStatus('评价提交失败，请稍后重试');
                    return;
                }
                const data = await resp.json();
                if (data && data.reply) {
                    this.addAgent(data.reply);
                } else {
                    this.addAgent('已记录你的看法，并更新个性化偏好。');
                }
                const applied = [];
                if (data && data.appliedArtists && Object.keys(data.appliedArtists).length) {
                    applied.push(`艺人：${Object.keys(data.appliedArtists).join(' / ')}`);
                }
                if (data && data.appliedKeywords && Object.keys(data.appliedKeywords).length) {
                    applied.push(`关键词：${Object.keys(data.appliedKeywords).join(' / ')}`);
                }
                if (applied.length) {
                    this.addStatus(`已更新偏好：${applied.join(' | ')}`);
                }
                this.closeFeedback();
            } catch (e) {
                this.addStatus('评价提交失败，请检查网络');
            } finally {
                this.feedbackSending = false;
            }
        },
        ensureVideoBvid(video) {
            if (!video) return;
            if (!video.bvid) {
                const bvid = this.extractBvid(video.url || '');
                if (bvid) {
                    video.bvid = bvid;
                }
            }
        },
        async ensureMp3(video) {
            if (!video) {
                throw new Error('视频为空');
            }
            this.ensureVideoBvid(video);
            if (video._mp3Error) {
                throw new Error(video._mp3Error);
            }
            if (video._mp3Url) {
                return video._mp3Url;
            }
            if (video._mp3Promise) {
                return video._mp3Promise;
            }
            if (!video.bvid && !video.url) {
                const msg = '缺少视频地址，无法下载 MP3';
                video._mp3Error = msg;
                throw new Error(msg);
            }
            const promise = (async () => {
                const resp = await fetch('/api/media/mp3', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        bvid: video.bvid || '',
                        url: video.url || ''
                    })
                });
                if (!resp.ok) {
                    let msg = 'MP3 下载失败';
                    try {
                        const data = await resp.json();
                        if (data && (data.message || data.error)) {
                            msg = data.message || data.error;
                        }
                    } catch (e) {
                        try {
                            const text = await resp.text();
                            if (text) {
                                msg = text;
                            }
                        } catch (ignored) {}
                    }
                    video._mp3Error = msg;
                    throw new Error(msg);
                }
                const data = await resp.json();
                const streamUrl = data && (data.streamUrl || data.downloadUrl);
                const downloadUrl = data && (data.downloadUrl || data.streamUrl);
                if (!streamUrl) {
                    const msg = 'MP3 地址缺失';
                    video._mp3Error = msg;
                    throw new Error(msg);
                }
                video._mp3Url = streamUrl;
                if (downloadUrl) {
                    video._mp3DownloadUrl = downloadUrl;
                }
                return streamUrl;
            })();
            video._mp3Promise = promise.finally(() => {
                video._mp3Promise = null;
            });
            return video._mp3Promise;
        },
        async downloadMp3(video) {
            try {
                this.addStatus('正在准备下载 MP3...');
                const streamUrl = await this.ensureMp3(video);
                const downloadUrl = this.getMp3DownloadUrl(video, streamUrl);
                const fileName = this.buildDownloadFileName(video);
                const saved = await this.trySaveWithPicker(downloadUrl, fileName);
                if (!saved) {
                    this.triggerBrowserDownload(downloadUrl, fileName);
                    this.addStatus('已触发浏览器下载');
                } else {
                    this.addStatus('已保存到本地');
                }
            } catch (e) {
                this.addStatus(`MP3 下载失败: ${e && e.message ? e.message : '未知错误'}`);
            }
        },
        prefetchAround(index) {
            if (!Array.isArray(this.playlist) || this.playlist.length === 0) {
                return;
            }
            const targets = [];
            if (index + 1 < this.playlist.length) targets.push(index + 1);
            if (index + 2 < this.playlist.length) targets.push(index + 2);
            targets.forEach(i => this.prefetchMp3ByIndex(i));
        },
        prefetchMp3ByIndex(index) {
            if (index < 0 || index >= this.playlist.length) return;
            const video = this.playlist[index];
            if (!video || video._mp3Url || video._mp3Promise) return;
            this.ensureMp3(video).catch(() => {});
        },
        getMp3DownloadUrl(video, fallbackUrl) {
            if (video && video._mp3DownloadUrl) {
                return video._mp3DownloadUrl;
            }
            if (video && video.bvid) {
                return `/api/media/mp3/${video.bvid}/download`;
            }
            return fallbackUrl;
        },
        buildDownloadFileName(video) {
            const base = this.getVideoTitle(video)
                .replace(/[\\/:*?"<>|]/g, '')
                .trim();
            return base ? `${base}.mp3` : 'bilibili-music.mp3';
        },
        triggerBrowserDownload(url, fileName) {
            if (!url) {
                this.addStatus('下载链接不可用');
                return;
            }
            const link = document.createElement('a');
            link.href = url;
            if (fileName) {
                link.download = fileName;
            }
            link.rel = 'noopener';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        },
        async trySaveWithPicker(url, fileName) {
            if (!window.showSaveFilePicker) {
                return false;
            }
            try {
                const handle = await window.showSaveFilePicker({
                    suggestedName: fileName || 'bilibili-music.mp3',
                    types: [
                        {
                            description: 'MP3 音频',
                            accept: { 'audio/mpeg': ['.mp3'] }
                        }
                    ]
                });
                const resp = await fetch(url);
                if (!resp.ok) {
                    throw new Error('下载失败');
                }
                const blob = await resp.blob();
                const writable = await handle.createWritable();
                await writable.write(blob);
                await writable.close();
                return true;
            } catch (e) {
                return false;
            }
        },
        showPlayHint() {
            if (this.playHintTimer) {
                clearTimeout(this.playHintTimer);
            }
            this.playHintVisible = true;
            this.playHintTimer = setTimeout(() => {
                this.playHintVisible = false;
                this.playHintTimer = null;
            }, 1400);
        },
        restartFromStart() {
            const audio = this.$refs.audioPlayer;
            if (!audio) {
                return;
            }
            audio.currentTime = 0;
            this.audioCurrentTime = 0;
            if (this.isPlaying) {
                this.syncVideoWithAudio(true);
                this.showPlayHint();
            } else if (this.showVideoContent) {
                this.playerSrc = '';
            }
        },
        handleAudioEnded() {
            this.isPlaying = false;
            this.playNext();
        },
        onAudioPlay() {
            this.isPlaying = true;
        },
        onAudioPause() {
            this.isPlaying = false;
        },
        onAudioTimeUpdate(event) {
            if (this.isSeeking) {
                return;
            }
            const audio = event && event.target ? event.target : this.$refs.audioPlayer;
            if (!audio) return;
            if (Number.isFinite(audio.currentTime)) {
                this.audioCurrentTime = Math.floor(audio.currentTime);
            }
            if (Number.isFinite(audio.duration)) {
                this.audioDuration = Math.floor(audio.duration);
            }
        },
        onAudioLoadedMetadata(event) {
            const audio = event && event.target ? event.target : this.$refs.audioPlayer;
            if (!audio) return;
            if (Number.isFinite(audio.duration)) {
                this.audioDuration = Math.floor(audio.duration);
            }
            if (Number.isFinite(audio.currentTime)) {
                this.audioCurrentTime = Math.floor(audio.currentTime);
            }
        },
        onSeekInput(event) {
            const value = Number(event.target.value);
            if (!Number.isFinite(value)) return;
            this.isSeeking = true;
            this.audioCurrentTime = Math.floor(value);
        },
        onSeekCommit(event) {
            const value = Number(event.target.value);
            if (!Number.isFinite(value)) {
                this.isSeeking = false;
                return;
            }
            const audio = this.$refs.audioPlayer;
            if (audio) {
                audio.currentTime = value;
                if (this.isPlaying) {
                    this.syncVideoWithAudio(false);
                } else if (this.showVideoContent) {
                    this.playerSrc = '';
                }
            }
            this.isSeeking = false;
        },
        formatTime(value) {
            if (!Number.isFinite(value) || value < 0) {
                return '0:00';
            }
            const total = Math.floor(value);
            const m = Math.floor(total / 60);
            const s = total % 60;
            return `${m}:${String(s).padStart(2, '0')}`;
        },
        extractBvid(url) {
            if (!url) return null;
            const match = url.match(/\/video\/(BV[a-zA-Z0-9]+)/);
            return match ? match[1] : null;
        },
        buildPlayerSrc(bvid, startSeconds) {
            const start = startSeconds && startSeconds > 0 ? `&t=${Math.floor(startSeconds)}` : '';
            const ts = Date.now();
            return `https://player.bilibili.com/player.html?bvid=${bvid}&page=1&high_quality=1&autoplay=1&muted=1${start}&ts=${ts}`;
        },
        syncVideoWithAudio(forceStart) {
            if (!this.showVideoContent) {
                return;
            }
            const video = this.currentVideo;
            if (!video) return;
            this.ensureVideoBvid(video);
            const bvid = video.bvid || this.extractBvid(video.url || '');
            if (!bvid) return;
            const audio = this.$refs.audioPlayer;
            let startSeconds = 0;
            if (!forceStart && audio && !Number.isNaN(audio.currentTime)) {
                startSeconds = Math.floor(audio.currentTime);
            }
            this.playerSrc = this.buildPlayerSrc(bvid, startSeconds);
            this.lastPlayerSrc = this.playerSrc;
        },
        async playVideo(index) {
            if (index < 0 || index >= this.playlist.length) return;
            this.currentVideoIndex = index;
            const video = this.playlist[index];
            this.ensureVideoBvid(video);
            if (!this.showVideoContent) {
                this.playerSrc = '';
            }

            const requestSeq = ++this.playRequestSeq;
            this.isDownloading = true;
            try {
                const mp3Url = await this.ensureMp3(video);
                if (requestSeq !== this.playRequestSeq) {
                    return;
                }
                this.audioSrc = mp3Url;
                await this.$nextTick();
                const audio = this.$refs.audioPlayer;
                if (audio) {
                    audio.load();
                    const playPromise = audio.play();
                    if (playPromise) {
                        playPromise.then(() => {
                            this.isPlaying = true;
                            this.syncVideoWithAudio(true);
                            this.showPlayHint();
                        }).catch(() => {
                            this.isPlaying = false;
                            this.addStatus('音频播放失败，请点击播放按钮重试');
                        });
                    } else {
                        this.isPlaying = true;
                        this.syncVideoWithAudio(true);
                        this.showPlayHint();
                    }
                }
                this.prefetchAround(index);
            } catch (e) {
                this.isPlaying = false;
                this.addStatus(`音频加载失败: ${e && e.message ? e.message : '未知错误'}`);
            } finally {
                if (requestSeq === this.playRequestSeq) {
                    this.isDownloading = false;
                }
            }
        },
        togglePlayPause() {
            const audio = this.$refs.audioPlayer;
            if (!audio) {
                return;
            }
            if (this.isPlaySwitching) {
                return;
            }
            if (!this.audioSrc && this.currentVideoIndex >= 0) {
                this.playVideo(this.currentVideoIndex);
                return;
            }
            if (this.isPlaying) {
                this.isPlaySwitching = true;
                audio.pause();
                this.isPlaying = false;
                if (this.showVideoContent) {
                    this.playerSrc = '';
                }
                this.isPlaySwitching = false;
            } else {
                this.isPlaySwitching = true;
                const playPromise = audio.play();
                if (playPromise) {
                    playPromise.then(() => {
                        this.isPlaying = true;
                        this.syncVideoWithAudio(false);
                        this.showPlayHint();
                        this.isPlaySwitching = false;
                    }).catch(() => {
                        this.isPlaying = false;
                        this.isPlaySwitching = false;
                        this.addStatus('音频播放失败，请稍后重试');
                    });
                } else {
                    this.isPlaying = true;
                    this.syncVideoWithAudio(false);
                    this.showPlayHint();
                    this.isPlaySwitching = false;
                }
            }
        },
        playNext() {
            if (this.playlist.length === 0) return;
            if (this.playMode === 'repeat-one') {
                this.playVideo(this.currentVideoIndex);
                return;
            }
            if (this.playMode === 'shuffle') {
                if (!this.shuffleOrder.length) {
                    this.rebuildShuffleOrder();
                }
                this.shufflePos = (this.shufflePos + 1) % this.shuffleOrder.length;
                this.playVideo(this.shuffleOrder[this.shufflePos]);
                return;
            }
            const nextIndex = (this.currentVideoIndex + 1) % this.playlist.length;
            this.playVideo(nextIndex);
        },
        playPrev() {
            if (this.playlist.length === 0) return;
            if (this.playMode === 'repeat-one') {
                this.playVideo(this.currentVideoIndex);
                return;
            }
            if (this.playMode === 'shuffle') {
                if (!this.shuffleOrder.length) {
                    this.rebuildShuffleOrder();
                }
                this.shufflePos = (this.shufflePos - 1 + this.shuffleOrder.length) % this.shuffleOrder.length;
                this.playVideo(this.shuffleOrder[this.shufflePos]);
                return;
            }
            const prevIndex = (this.currentVideoIndex - 1 + this.playlist.length) % this.playlist.length;
            this.playVideo(prevIndex);
        },
        onPlayModeChange() {
            if (this.playMode === 'shuffle') {
                this.rebuildShuffleOrder();
            }
        },
        rebuildShuffleOrder() {
            this.shuffleOrder = this.playlist.map((_, idx) => idx);
            for (let i = this.shuffleOrder.length - 1; i > 0; i--) {
                const j = Math.floor(Math.random() * (i + 1));
                [this.shuffleOrder[i], this.shuffleOrder[j]] = [this.shuffleOrder[j], this.shuffleOrder[i]];
            }
            this.shufflePos = Math.max(0, this.shuffleOrder.indexOf(this.currentVideoIndex));
        },
        onDragStart(index) {
            this.dragStartIndex = index;
        },
        onDragOver(index) {
            this.dragOverIndex = index;
        },
        onDrop(index) {
            const from = this.dragStartIndex;
            const to = index;
            if (from == null || to == null || from === to) {
                this.dragStartIndex = null;
                this.dragOverIndex = null;
                return;
            }
            const moved = this.playlist.splice(from, 1)[0];
            this.playlist.splice(to, 0, moved);
            if (this.currentVideoIndex === from) {
                this.currentVideoIndex = to;
            } else if (from < this.currentVideoIndex && to >= this.currentVideoIndex) {
                this.currentVideoIndex -= 1;
            } else if (from > this.currentVideoIndex && to <= this.currentVideoIndex) {
                this.currentVideoIndex += 1;
            }
            this.rebuildShuffleOrder();
            this.dragStartIndex = null;
            this.dragOverIndex = null;
        },
        onDragEnd() {
            this.dragStartIndex = null;
            this.dragOverIndex = null;
        },
        sendMessage() {
            const text = this.userInput.trim();
            if (!text) return;
            this.addUser(text);
            if (this.stompClient && this.wsConnected) {
                let mode = this.runMode || '';
                if (this.albumOrder) {
                    mode = mode ? `${mode}+album_order` : 'album_order';
                }
                const limit = Number.isFinite(this.resultLimit) && this.resultLimit > 0 ? this.resultLimit : 10;
                const message = {
                    type: 'query',
                    content: text,
                    limit,
                    payload: {
                        mode,
                        limit,
                        preference: this.preference || ''
                    }
                };
                this.stompClient.send('/app/chat', {}, JSON.stringify(message));
            } else {
                this.addStatus('连接未建立，请稍后重试');
            }
            this.userInput = '';
        },
        onInputKeydown(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                this.sendMessage();
            }
        },
        continueAdding() {
            if (!this.playlist.length) return;
            const artistCount = {};
            this.playlist.forEach(video => {
                if (video.author) {
                    artistCount[video.author] = (artistCount[video.author] || 0) + 1;
                }
            });
            let mainArtist = '';
            let maxCount = 0;
            Object.keys(artistCount).forEach(artist => {
                if (artistCount[artist] > maxCount) {
                    maxCount = artistCount[artist];
                    mainArtist = artist;
                }
            });
            const query = mainArtist ? `继续找更多${mainArtist}的歌` : '继续找更多相似的歌';
            this.userInput = query;
            this.sendMessage();
        },
        savePlaylist() {
            const now = new Date();
            const dateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
            const defaultName = `我的歌单 ${dateStr} (${this.playlist.length}首)`;
            const name = window.prompt('请给你的歌单起个名字：', defaultName);
            if (!name) return;
            const playlistId = 1;
            fetch('/api/playlist/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ playlistId, name })
            })
                .then(resp => {
                    if (resp.ok) {
                        this.addStatus(`💾 已保存播放列表：${name}`);
                    } else {
                        this.addStatus('保存失败，请稍后重试');
                    }
                })
                .catch(() => this.addStatus('保存请求失败，请检查网络连接'));
        },
        likeVideo(video) {
            if (!video || !video.itemId) {
                this.addStatus('当前视频暂无可点赞 ID');
                return;
            }
            fetch(`/api/playlist/item/${video.itemId}/like`, { method: 'POST' })
                .then(() => this.addStatus('已记录喜欢'))
                .catch(() => this.addStatus('点赞失败'));
        },
        showWhy(video) {
            const decision = video ? video.__decision : null;
            if (!decision) {
                this.addStatus('暂无决策信息');
                return;
            }
            const features = decision.features || {};
            const safeText = (value, fallback = 'N/A') => {
                if (value === null || value === undefined || value === '') {
                    return fallback;
                }
                return value;
            };
            const toNumber = (value) => {
                const num = Number(value);
                return Number.isFinite(num) ? num : 0;
            };
            const keywordScore = toNumber(features.titleScore)
                + toNumber(features.tagScore)
                + toNumber(features.descriptionScore)
                + toNumber(features.authorScore);
            const lines = [
                `reasonCode: ${safeText(decision.reasonCode)}`,
                `score: ${safeText(decision.score)} (base=${safeText(decision.baseScore)}, adj=${safeText(decision.modelAdjustment)})`,
                `semanticScore: ${safeText(features.semanticScore)}`,
                `keywordScore: ${keywordScore}`,
                `credibilityScore: ${safeText(features.credibilityScore)}`,
                `variant: ${safeText(decision.variant)}`,
                `model: ${safeText(decision.modelName)} ${safeText(decision.modelVersion, '')}`.trim()
            ];
            this.whyDetail = {
                title: video.title || '推荐解释',
                lines
            };
        },
        closeWhy() {
            this.whyDetail = null;
        },
        loadConversations() {
            fetch('/api/conversations')
                .then(resp => resp.json())
                .then(data => {
                    this.conversations = Array.isArray(data) ? data : [];
                    const active = this.conversations.find(conv => conv.status === 'ACTIVE');
                    if (active) {
                        this.currentConversationId = active.id;
                    }
                })
                .catch(() => {
                    this.addStatus('加载对话列表失败');
                });
        },
        createNewConversation() {
            fetch('/api/conversations', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: '新对话' })
            })
                .then(resp => resp.json())
                .then(conv => {
                    this.currentConversationId = conv.id;
                    this.playlist = [];
                    this.currentVideoIndex = -1;
                    this.playerSrc = '';
                    this.audioSrc = '';
                    this.isPlaying = false;
                    this.isDownloading = false;
                    this.messages = [];
                    this.agentSummary = '';
                    this.loadConversations();
                    this.addStatus('已创建新对话');
                })
                .catch(() => this.addStatus('创建对话失败'));
        },
        switchConversation(id) {
            if (id === this.currentConversationId) return;
            fetch(`/api/conversations/${id}/switch`, { method: 'PUT' })
                .then(() => {
                    this.currentConversationId = id;
                    this.playlist = [];
                    this.currentVideoIndex = -1;
                    this.playerSrc = '';
                    this.audioSrc = '';
                    this.isPlaying = false;
                    this.isDownloading = false;
                    this.messages = [];
                    this.agentSummary = '';
                    this.addStatus(`已切换到对话 ${id}`);
                })
                .catch(() => this.addStatus('切换对话失败'));
        },
        deleteConversation(id) {
            if (!window.confirm('确定删除该对话吗？')) return;
            fetch(`/api/conversations/${id}`, { method: 'DELETE' })
                .then(() => {
                    if (id === this.currentConversationId) {
                        this.createNewConversation();
                    } else {
                        this.loadConversations();
                    }
                    this.addStatus('对话已删除');
                })
                .catch(() => this.addStatus('删除对话失败'));
        },
        formatConversationTime(iso) {
            if (!iso) return '';
            const date = new Date(iso);
            if (Number.isNaN(date.getTime())) return '';
            return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
        },
        toggleExecutionHistory() {
            this.executionHistoryVisible = !this.executionHistoryVisible;
            if (this.executionHistoryVisible && this.recentExecutions.length === 0) {
                this.loadRecentExecutions();
            }
        },
        loadRecentExecutions() {
            fetch('/api/playlist/observability/recent-executions?limit=10')
                .then(resp => resp.json())
                .then(data => {
                    this.recentExecutions = Array.isArray(data) ? data : [];
                    this.selectedExecutionKeys = [];
                })
                .catch(() => {
                    this.addStatus('加载执行历史失败');
                });
        },
        isExecutionSelected(exec) {
            const key = `${exec.playlistId}|${exec.latestExecutionId || ''}`;
            return this.selectedExecutionKeys.includes(key);
        },
        toggleExecutionSelection(exec, event) {
            const key = `${exec.playlistId}|${exec.latestExecutionId || ''}`;
            if (event.target.checked) {
                if (!exec.latestExecutionId) {
                    event.target.checked = false;
                    this.addStatus('执行缺少 executionId');
                    return;
                }
                this.selectedExecutionKeys.push(key);
                if (this.selectedExecutionKeys.length > 2) {
                    this.selectedExecutionKeys.shift();
                }
            } else {
                this.selectedExecutionKeys = this.selectedExecutionKeys.filter(item => item !== key);
            }
        },
        compareSelectedExecutions() {
            if (this.selectedExecutionKeys.length !== 2) {
                this.addStatus('请选择两条执行进行对比');
                return;
            }
            const [k1, k2] = this.selectedExecutionKeys;
            const [p1, e1] = k1.split('|');
            const [p2, e2] = k2.split('|');
            Promise.all([
                fetch(`/api/playlist/debug/trace?playlistId=${p1}&executionId=${encodeURIComponent(e1)}`).then(r => r.json()),
                fetch(`/api/playlist/debug/trace?playlistId=${p2}&executionId=${encodeURIComponent(e2)}`).then(r => r.json())
            ])
                .then(([t1, t2]) => {
                    this.executionCompareRows = this.buildCompareRows(t1, t2);
                })
                .catch(() => this.addStatus('执行对比失败'));
        },
        buildCompareRows(t1, t2) {
            if (!t1 || !t2) return [];
            const metrics = [
                { key: 'executionId', label: '执行ID' },
                { key: 'playlistId', label: 'PlaylistID' },
                { key: 'conversationId', label: 'ConversationID' },
                { key: 'status', label: '状态' },
                { key: 'fsmState', label: 'FSM 状态' },
                { key: 'totalDurationMs', label: '总耗时(ms)' },
                { key: 'graphVersion', label: '策略版本' },
                { key: 'contextVersion', label: '上下文版本' }
            ];
            const rows = metrics.map(metric => ({
                label: metric.label,
                v1: t1[metric.key] ?? '',
                v2: t2[metric.key] ?? ''
            }));
            rows.push({
                label: '节点数',
                v1: t1.nodeTraces ? t1.nodeTraces.length : 0,
                v2: t2.nodeTraces ? t2.nodeTraces.length : 0
            });
            rows.push({
                label: '边数',
                v1: t1.edgeTraces ? t1.edgeTraces.length : 0,
                v2: t2.edgeTraces ? t2.edgeTraces.length : 0
            });
            return rows;
        },
        loadObservabilityStats() {
            Promise.all([
                fetch('/api/playlist/observability/prompt-versions').then(r => r.json()),
                fetch('/api/playlist/observability/llm-stats').then(r => r.json()),
                fetch('/api/playlist/observability/decision-stats').then(r => r.json()),
                fetch('/api/playlist/observability/error-stats?hours=24').then(r => r.json()),
                fetch('/api/playlist/observability/capabilities').then(r => r.json())
            ])
                .then(([promptVersions, llmStats, decisionStats, errorStats, capabilities]) => {
                    this.observability = {
                        promptVersions,
                        llmCalls: llmStats?.totalCalls ?? '',
                        nodeCount: llmStats?.nodes ? llmStats.nodes.length : 0,
                        decisionTotal: decisionStats?.totalDecisions ?? '',
                        feedbackTotal: decisionStats?.feedbackTotal ?? '',
                        feedbackAccuracy: decisionStats?.feedbackAccuracy ?? '',
                        errorTotal: errorStats?.totalErrors ?? '',
                        capabilities: capabilities || {}
                    };
                })
                .catch(() => this.addStatus('加载统计失败'));
        },
        formatExecutionTime(text) {
            if (!text) return '';
            return text.replace('T', ' ');
        },
        formatPreferenceBonus(bonus) {
            if (!bonus) return '暂无';
            const parts = Object.keys(bonus).map(key => `${key}:${bonus[key]}`);
            return parts.join(' | ') || '暂无';
        }
    }
}).mount('#app');
