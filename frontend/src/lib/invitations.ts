import { graphQl } from "./http";

export type InvitationCode = {
  id: string;
  code: string;
  createdAt: string;
};

export type ViewerInvitationSummary = {
  codes: InvitationCode[];
  availableCodeCount: number;
  invitedUserCount: number;
  generationLimit: number;
  neverExpire: boolean;
};

/** The signed-in account's available codes and the registration policy they follow. */
export async function fetchViewerInvitations(
  accessToken: string
): Promise<ViewerInvitationSummary> {
  const data = await graphQl<{ viewerInvitations: ViewerInvitationSummary }>(
    accessToken,
    `query ViewerInvitations {
       viewerInvitations {
         codes { id code createdAt }
         availableCodeCount
         invitedUserCount
         generationLimit
         neverExpire
       }
     }`
  );
  return data.viewerInvitations;
}

export async function createInvitationCode(
  accessToken: string
): Promise<InvitationCode> {
  const data = await graphQl<{ createInvitationCode: InvitationCode }>(
    accessToken,
    `mutation CreateInvitationCode {
       createInvitationCode { id code createdAt }
     }`
  );
  return data.createInvitationCode;
}
