import sys, ssl, asyncio, edge_tts, edge_tts.communicate as c
c._SSL_CTX = ssl.create_default_context(cafile="/root/.ccr/ca-bundle.crt")
async def main(text, out, rate, pitch, vtt):
    com = edge_tts.Communicate(text, "id-ID-ArdiNeural", rate=rate, pitch=pitch, boundary="WordBoundary")
    sub = edge_tts.SubMaker()
    with open(out, "wb") as f:
        async for ch in com.stream():
            if ch["type"] == "audio": f.write(ch["data"])
            elif ch["type"] in ("WordBoundary","SentenceBoundary"): sub.feed(ch)
    if vtt: open(vtt,"w").write(sub.get_srt())
asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv)>3 else "+0%", sys.argv[4] if len(sys.argv)>4 else "+0Hz", sys.argv[5] if len(sys.argv)>5 else None))
