import React from "react";
import { Avatar, Typography } from "@material-ui/core";
import SignOut from "./SignOut";

export default function AccountSignedIn(props) {
  const { displayName, photoURL } = props.auth.currentUser;

  console.log(props.auth.currentUser);

  function stringToColor(string) {
    let hash = 0;
    let i;

    /* eslint-disable no-bitwise */
    for (i = 0; i < string.length; i += 1) {
      hash = string.charCodeAt(i) + ((hash << 5) - hash);
    }

    let color = "#";

    for (i = 0; i < 3; i += 1) {
      const value = (hash >> (i * 8)) & 0xff;
      color += `00${value.toString(16)}`.substr(-2);
    }
    /* eslint-enable no-bitwise */

    return color;
  }

  function stringAvatar(name) {
    return {
      sx: {
        bgcolor: stringToColor(name),
      },
      children: `${name.split(" ")[0][0]}${name.split(" ")[1][0]}`,
    };
  }

  return (
    <React.Fragment>
      <Typography>{displayName}</Typography>
      <Avatar
        alt="Current User Avatar"
        src={photoURL}
        style={{ margin: "5px" }}
        {...stringAvatar(displayName)}
      />
      <SignOut auth={props.auth} firebase={props.firebase} />
    </React.Fragment>
  );
}
