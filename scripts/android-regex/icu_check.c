#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
typedef uint16_t UChar;
typedef int UErrorCode;
typedef struct { int32_t line; int32_t offset; UChar preContext[16]; UChar postContext[16]; } UParseError;
typedef struct URegularExpression URegularExpression;
URegularExpression* uregex_open_60(const UChar*, int32_t, uint32_t, UParseError*, UErrorCode*);
void uregex_close_60(URegularExpression*);
const char* u_errorName_60(UErrorCode);
UChar* u_strFromUTF8_60(UChar*, int32_t, int32_t*, const char*, int32_t, UErrorCode*);
/* Compiles regexes with ICU (symbols of ICU 60). stdin: "ID\tFLAGS\tPATTERN" per line, \n and \\ escaped. Prints "ID\tok" or "ID\tFAIL <error> at <offset>". */
int main(void) {
  static char line[1 << 16]; static char pat[1 << 16]; static UChar u[1 << 16];
  while (fgets(line, sizeof line, stdin)) {
    size_t n = strlen(line); if (n && line[n-1] == '\n') line[--n] = 0;
    char *id = line, *t1 = strchr(line, '\t'); if (!t1) continue; *t1 = 0;
    char *fl = t1 + 1, *t2 = strchr(fl, '\t'); if (!t2) continue; *t2 = 0;
    char *src = t2 + 1; size_t k = 0;
    for (size_t i = 0; src[i]; i++) {
      if (src[i] == '\\' && src[i+1] == 'n') { pat[k++] = '\n'; i++; }
      else if (src[i] == '\\' && src[i+1] == '\\') { pat[k++] = '\\'; i++; }
      else pat[k++] = src[i];
    }
    pat[k] = 0;
    UErrorCode st = 0; int32_t len = 0;
    u_strFromUTF8_60(u, 1 << 16, &len, pat, (int32_t)k, &st);
    if (st > 0) { printf("%s\tUTF8 %s\n", id, u_errorName_60(st)); continue; }
    UParseError pe; st = 0;
    URegularExpression *re = uregex_open_60(u, len, (uint32_t)atoi(fl), &pe, &st);
    if (st > 0) printf("%s\tFAIL %s at %d\n", id, u_errorName_60(st), pe.offset);
    else printf("%s\tok\n", id);
    if (re) uregex_close_60(re);
  }
  return 0;
}
