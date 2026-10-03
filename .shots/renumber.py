import pathlib, re
p = pathlib.Path("docs/M8-STATUS.md")
t = p.read_text(encoding="utf-8")

# Section 8 disappeared when the round-2 addendum was inserted as 9 and "还没做的" became 10.
# Renumber so the document reads straight through.
t = t.replace("## 9. 第 2 轮补充", "## 8. 第 2 轮补充")
for i in (1, 2, 3, 4, 5):
    t = t.replace(f"### 9.{i} ", f"### 8.{i} ")
t = t.replace("## 10. 还没做的", "## 9. 还没做的")
t = t.replace("### 10.0 ", "### 9.0 ").replace("### 10.1 ", "### 9.1 ")
t = t.replace("见 §9.2", "见 §8.2").replace("见 §9.3", "见 §8.3")

# The section-5 title still advertised the two candidates as pending.
t = t.replace("## 5. 第 4 项：参考小米自带闹钟 —— 已借鉴两项，候选两项",
              "## 5. 第 4 项：参考小米自带闹钟 —— 已借鉴四项，候选已用完")
p.write_text(t, encoding="utf-8", newline="\n")
print("renumbered")
