"""Research sources beyond web search. All free/no-key except YouTube (YOUTUBE_API_KEY)."""
import gzip, json, os, urllib.parse as up, urllib.request as ur

UA = {"User-Agent": "iris-research/1.0"}

def _get(url, headers=None):
    req = ur.Request(url, headers={**UA, **(headers or {})})
    with ur.urlopen(req, timeout=20) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        return json.loads(raw.decode())

def _safe(fn):
    def w(**kw):
        try:
            return fn(**kw)
        except Exception as e:
            return f"ERROR: {e}"
    return w

@_safe
def github(query):
    h = {"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}"} if os.getenv("GITHUB_TOKEN") else {}
    d = _get("https://api.github.com/search/repositories?per_page=6&sort=stars&q=" + up.quote(query), h)
    return "\n".join(f"{i['full_name']} | stars {i['stargazers_count']} | last push {i['pushed_at'][:10]} | "
                     f"{i['description']} | {i['html_url']}" for i in d["items"]) or "no results"

@_safe
def stackoverflow(query):
    d = _get("https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=votes&pagesize=6"
             "&site=stackoverflow&q=" + up.quote(query))
    import datetime as dt
    return "\n".join(f"{i['title']} | score {i['score']} | answered={i['is_answered']} | "
                     f"{dt.date.fromtimestamp(i['creation_date'])} | {i['link']}" for i in d["items"]) or "no results"

@_safe
def reddit(query):
    d = _get("https://www.reddit.com/search.json?limit=6&q=" + up.quote(query))
    import datetime as dt
    return "\n".join(f"r/{c['data']['subreddit']} | {c['data']['title']} | score {c['data']['score']} | "
                     f"{dt.date.fromtimestamp(c['data']['created_utc'])} | https://reddit.com{c['data']['permalink']}"
                     for c in d["data"]["children"]) or "no results"

@_safe
def youtube(query):
    key = os.getenv("YOUTUBE_API_KEY")
    if not key:
        return "YouTube disabled: set YOUTUBE_API_KEY."
    d = _get("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&key="
             f"{key}&q=" + up.quote(query))
    return "\n".join(f"{i['snippet']['title']} | {i['snippet']['channelTitle']} | {i['snippet']['publishedAt'][:10]} | "
                     f"video_id={i['id']['videoId']}" for i in d["items"]) or "no results"

@_safe
def youtube_transcript(video_id):
    from youtube_transcript_api import YouTubeTranscriptApi
    t = YouTubeTranscriptApi.get_transcript(video_id)
    return " ".join(x["text"] for x in t)[:6000]

def _schema(name, desc, param, pdesc):
    return {"name": name, "description": desc,
            "input_schema": {"type": "object", "properties": {param: {"type": "string", "description": pdesc}},
                             "required": [param]}}

TOOLS = {
    "search_github": (_schema("search_github", "Find popular, recently-updated GitHub repos.", "query", "keywords"),
                      lambda query: github(query=query)),
    "search_stackoverflow": (_schema("search_stackoverflow", "Find top-voted Stack Overflow questions.", "query", "keywords"),
                             lambda query: stackoverflow(query=query)),
    "search_reddit": (_schema("search_reddit", "Search Reddit discussions.", "query", "keywords"),
                      lambda query: reddit(query=query)),
    "search_youtube": (_schema("search_youtube", "Search YouTube tutorials.", "query", "keywords"),
                       lambda query: youtube(query=query)),
    "youtube_transcript": (_schema("youtube_transcript", "Get a YouTube video's transcript.", "video_id", "video id"),
                           lambda video_id: youtube_transcript(video_id=video_id)),
}
