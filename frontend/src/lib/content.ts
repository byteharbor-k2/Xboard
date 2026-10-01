import { graphQl } from "../lib/http";

/**
 * User-side content fetches for the dashboard's announcement carousel and the
 * help centre, over the gateway's viewer queries. Only shown rows exist here;
 * an empty answer is the honest empty state, not a failure.
 */

export type ViewerNotice = {
  id: string;
  title: string;
  content: string;
  popup: boolean;
  publishedAt: string;
};

export type ViewerKnowledgeGroup = {
  category: string;
  articles: ViewerKnowledgeSummary[];
};

export type ViewerKnowledgeSummary = {
  id: string;
  title: string;
  updatedAt: string;
};

export type ViewerKnowledgeArticle = {
  id: string;
  title: string;
  category: string;
  language: string;
  body: string;
  updatedAt: string;
};

export function fetchViewerNotices(accessToken: string) {
  return graphQl<{ viewerNotices: ViewerNotice[] }>(
    accessToken,
    `query ViewerNotices {
      viewerNotices {
        id
        title
        content
        popup
        publishedAt
      }
    }`
  ).then((result) => result.viewerNotices);
}

export function fetchViewerKnowledge(
  accessToken: string,
  language: string,
  keyword = ""
) {
  return graphQl<{ viewerKnowledge: ViewerKnowledgeGroup[] }>(
    accessToken,
    `query ViewerKnowledge($language: String, $keyword: String) {
      viewerKnowledge(language: $language, keyword: $keyword) {
        category
        articles {
          id
          title
          updatedAt
        }
      }
    }`,
    {
      language: language.trim(),
      keyword: keyword.trim() || undefined
    }
  ).then((result) => result.viewerKnowledge);
}

export function fetchViewerKnowledgeArticle(accessToken: string, id: string) {
  return graphQl<{ viewerKnowledgeArticle: ViewerKnowledgeArticle | null }>(
    accessToken,
    `query ViewerKnowledgeArticle($id: ID!) {
      viewerKnowledgeArticle(id: $id) {
        id
        title
        category
        language
        body
        updatedAt
      }
    }`,
    { id }
  ).then((result) => result.viewerKnowledgeArticle);
}
