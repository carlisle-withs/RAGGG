a = '"启明行动"是为了防控儿童青少年的近视问题'
print(f"第一个字符: {repr(a[0])} ord={ord(a[0])}")
print(f"第二个字符: {repr(a[1])} ord={ord(a[1])}")
print(f"字符内容: {a[:4]}")
print(f"期望 U+201C={hex(0x201C)} U+201D={hex(0x201D)} U+0022={hex(0x0022)}")

# 直接用字符
left = "\u201c"  # "
right = "\u201d"  # "
print(f"\nleft={repr(left)} right={repr(right)}")
print(f"a中引号位置: {[i for i,c in enumerate(a) if c in ('\u201c','\u201d','"', '"')]}")
